#!/usr/bin/env python3
"""Eligibility, state freshness, branch integrity and paired split tests."""
import copy
import json
import unittest

from bounded_specialist_contract import build_turn, decode_choice, eligible_catalog, paired_turn, phone_choices, resolve_choice, rollout_episode
from make_bounded_specialist_training import generate, snapshot
from make_bounded_specialist_flat import flat_turn, resolve_flat_choice
from eval_bounded_specialist import presented_options
from add_specialist_reasoning import supervised_reasoning


class BoundedContractTest(unittest.TestCase):
    def test_reasoning_is_supervised_target_and_preserves_bound_inputs(self):
        _,rows=generate()
        row=next(paired_turn(r)[1] for r in rows if r["split"]=="train")
        before=copy.deepcopy(row)
        text=supervised_reasoning(row)
        name=row["expectedChoice"]
        self.assertIn(name,text)
        self.assertIn(row["turn"]["options"][name]["label"],text)
        self.assertEqual(row,before)
        row["answers"][0]["name"]="invented"
        with self.assertRaises(ValueError):
            supervised_reasoning(row)

    def test_reverse_presentation_preserves_host_ids_and_effects(self):
        state=snapshot()
        turn=build_turn(state,"change model",[],"presentation")
        original=copy.deepcopy(turn)
        options=presented_options(turn,reverse=True)
        self.assertEqual(list(options),list(reversed(turn["options"])))
        self.assertEqual(options,turn["options"])
        self.assertEqual(turn,original)
        for name in options:
            self.assertEqual(resolve_choice(turn,name,state),resolve_choice(original,name,state))

    def test_only_eligible_models_selectable_and_no_download_as_selection(self):
        state = snapshot()
        installed = build_turn(state,"change model",["settings","ai","local_models","active_model","installed"],"x")
        self.assertEqual({b["effect"]["modelId"] for b in installed["options"].values() if b["key"].startswith("model:")},{"gemma4-e2b-it-litert"})
        download = build_turn(state,"change model",["settings","ai","local_models","active_model","download"],"y")
        bound = next(b for b in download["options"].values() if b["key"].startswith("download:"))
        self.assertEqual(bound["effect"]["kind"],"offer_model_download")
        self.assertFalse(bound["effect"]["selectAfterDownload"])

    def test_download_eligibility_matches_host_gates_and_unknown_rejected(self):
        for field,value in (("enabled",False),("comingSoon",True),("supported",False),("sourceUrl",None),("supported",None),("gatedDownload",True)):
            state = snapshot()
            state["models"][2][field] = value
            self.assertEqual(eligible_catalog(state),[],field)
        state = snapshot()
        state["downloadInFlight"] = True
        self.assertEqual(eligible_catalog(state),[])

    def test_root_stop_only_when_speaking_and_no_local_models_at_root(self):
        state = snapshot()
        state["speaking"] = False
        turn = build_turn(state,"change model",[],"root")
        keys = {b["key"] for b in turn["options"].values()}
        self.assertIn("alter_settings",keys)
        self.assertNotIn("stop_speaking",keys)
        self.assertFalse(any(k.startswith("model:") for k in keys))

    def test_current_session_facts_are_visible_to_the_model(self):
        state=snapshot()
        state["liveRunning"]=True
        state["liveAvailable"]=True
        turn=build_turn(state,"start Gemini Live",[],"running")
        status=json.loads(turn["query"])["status"]
        self.assertTrue(status["liveRunning"])
        self.assertTrue(status["liveAvailable"])
        self.assertNotIn("start_gemini_live",{b["key"] for b in turn["options"].values()})
        state["liveRunning"]=False;state["liveAvailable"]=False
        unavailable=build_turn(state,"start Gemini Live",[],"unavailable")
        self.assertFalse(json.loads(unavailable["query"])["status"]["liveRunning"])
        self.assertFalse(json.loads(unavailable["query"])["status"]["liveAvailable"])

    def test_stale_or_forged_binding_rejected(self):
        state = snapshot()
        turn = build_turn(state,"change model",[],"root")
        selected = next(iter(turn["options"]))
        changed = copy.deepcopy(state)
        changed["revision"] = "new"
        with self.assertRaises(ValueError):
            resolve_choice(turn,selected,changed)
        forged = copy.deepcopy(turn)
        forged["options"][selected]["effect"] = {"kind":"set_local_model","modelId":"invented"}
        with self.assertRaises(ValueError):
            resolve_choice(forged,selected,state)
        with self.assertRaises(ValueError):
            resolve_choice(turn,"invented",state)

    def test_unreachable_scope_cannot_offer_valid_looking_descendants(self):
        state = snapshot()
        state["localModelsAvailable"] = False
        with self.assertRaises(ValueError):
            build_turn(state,"change model",["settings","ai","local_models","active_model","installed"],"forged-scope")
        state["settingsAvailable"] = False
        with self.assertRaises(ValueError):
            build_turn(state,"change model",["settings","ai"],"forged-settings")

    def test_duplicate_model_binding_and_unknown_modes_rejected(self):
        state=snapshot()
        state["models"].append(copy.deepcopy(state["models"][0]))
        with self.assertRaises(ValueError):
            build_turn(state,"change model",[],"duplicate")
        state=snapshot()
        state["liveRefresh"]="2_seconds"
        with self.assertRaises(ValueError):
            build_turn(state,"change live refresh",[],"unknown-mode")

    def test_needle_cannot_invent_arguments_or_use_multiple_tools(self):
        turn = build_turn(snapshot(),"stop speaking",[],"root")
        name = next(iter(turn["options"]))
        call = {"name":name,"arguments":{}}
        self.assertEqual(decode_choice({"turn":turn},[call]),name)
        for calls in ([],None,[call,call],[dict(call,arguments={"model":"invented"})],[{"name":"invented","arguments":{}}],[{"name":["option_0"],"arguments":{}}]):
            self.assertIsNone(decode_choice({"turn":turn},calls))

    def test_group_before_state_variants_and_model_views_match(self):
        episodes,rows = generate()
        groups = {}
        snapshots = {ep["episodeId"]:ep["snapshot"] for ep in episodes}
        for row in rows:
            self.assertEqual(groups.setdefault(row["groupId"],row["split"]),row["split"])
            laya,needle = paired_turn(row)
            self.assertEqual(laya["state"],needle["query"])
            self.assertEqual(set(laya["questions"]["next_action"]["criteria"]),{t["name"] for t in needle["tools"]})
            self.assertEqual(laya["expectedCalls"],needle["answers"])
            self.assertEqual(resolve_choice(row["turn"],row["expectedChoice"],snapshots[row["episodeId"]]).get("effect"),row["expectedEffect"])

    def test_every_offered_choice_has_positive_training_supervision(self):
        _,rows=generate()
        rows=[r for r in rows if r["split"]=="train"]
        offered={b["key"] for r in rows for b in r["turn"]["options"].values()}
        selected={r["turn"]["options"][r["expectedChoice"]]["key"] for r in rows}
        self.assertEqual(offered-selected,set())

    def test_flat_uses_same_legal_terminal_effects(self):
        episodes,_ = generate()
        for ep in episodes:
            turn = flat_turn(ep["snapshot"],ep["request"],ep["episodeId"]+":0")
            matches = [name for name,b in turn["options"].items() if b["effect"]==ep["expectedEffect"]]
            self.assertEqual(len(matches),1)
            self.assertEqual(resolve_flat_choice(turn,matches[0],ep["snapshot"])["effect"],ep["expectedEffect"])

    def test_phone_menu_requires_explicit_verified_current_binding(self):
        base = {"id":"a","eligible":True,"bindingVerified":True,"observationRevision":"now"}
        candidates = [base,dict(base,id="old",observationRevision="old"),dict(base,id="disabled",eligible=False),{"id":"unknown"}]
        self.assertEqual(phone_choices(candidates,"now",True),[base])
        self.assertEqual(phone_choices(candidates,"now",False),[])
        self.assertEqual(phone_choices([dict(base,observationRevision=None)],None,True),[])
        self.assertEqual(phone_choices([dict(base,id=None)],"now",True),[])

    def test_wrong_first_route_not_rescued_by_oracle_scope(self):
        episodes,_ = generate()
        ep=next(ep for ep in episodes if ep["expectedEffect"]["kind"]=="set_local_model")
        def wrong_route(turn):
            return next(name for name,b in turn["options"].items() if b["key"]=="ai_image_question"),None
        result=rollout_episode(ep,wrong_route)
        self.assertFalse(result["correct"])
        self.assertEqual(len(result["trace"]),1)
        self.assertEqual(result["proposedEffect"]["kind"],"invoke_image_question")

    def test_rollout_invalid_choice_does_not_fallback_to_gold(self):
        episodes,_=generate()
        result=rollout_episode(episodes[0],lambda turn:("invented",None))
        self.assertFalse(result["correct"])
        self.assertIsNone(result["proposedEffect"])


if __name__ == "__main__":
    unittest.main()
