package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.content.res.Configuration
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import java.util.Locale

/** Translates detector labels only at output; English class identities remain stable for tracking. */
object WalkingAidSceneFormatter {
    fun format(context: Context, group: ExploreObjectGroup): String {
        val languageTag = ImageQuestionPreferences.get(context).appLanguageTag
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        })
        val noun = localized.resources.getQuantityString(labelResource(group.label), group.count)
        val position = localized.getString(when (group.position) {
            "left" -> R.string.walking_aid_position_left
            "right" -> R.string.walking_aid_position_right
            else -> R.string.walking_aid_position_center
        })
        return if (group.count == 1) {
            localized.getString(R.string.walking_aid_explore_single, noun, position)
        } else {
            localized.getString(R.string.walking_aid_explore_multiple, group.count, noun, position)
        }
    }

    internal fun labelResource(label: String): Int = when (label) {
        "person" -> R.plurals.walking_aid_object_person
        "bicycle" -> R.plurals.walking_aid_object_bicycle
        "car" -> R.plurals.walking_aid_object_car
        "motorcycle" -> R.plurals.walking_aid_object_motorcycle
        "airplane" -> R.plurals.walking_aid_object_airplane
        "bus" -> R.plurals.walking_aid_object_bus
        "train" -> R.plurals.walking_aid_object_train
        "truck" -> R.plurals.walking_aid_object_truck
        "boat" -> R.plurals.walking_aid_object_boat
        "traffic light" -> R.plurals.walking_aid_object_traffic_light
        "fire hydrant" -> R.plurals.walking_aid_object_fire_hydrant
        "stop sign" -> R.plurals.walking_aid_object_stop_sign
        "parking meter" -> R.plurals.walking_aid_object_parking_meter
        "bench" -> R.plurals.walking_aid_object_bench
        "bird" -> R.plurals.walking_aid_object_bird
        "cat" -> R.plurals.walking_aid_object_cat
        "dog" -> R.plurals.walking_aid_object_dog
        "horse" -> R.plurals.walking_aid_object_horse
        "sheep" -> R.plurals.walking_aid_object_sheep
        "cow" -> R.plurals.walking_aid_object_cow
        "elephant" -> R.plurals.walking_aid_object_elephant
        "bear" -> R.plurals.walking_aid_object_bear
        "zebra" -> R.plurals.walking_aid_object_zebra
        "giraffe" -> R.plurals.walking_aid_object_giraffe
        "backpack" -> R.plurals.walking_aid_object_backpack
        "umbrella" -> R.plurals.walking_aid_object_umbrella
        "handbag" -> R.plurals.walking_aid_object_handbag
        "tie" -> R.plurals.walking_aid_object_tie
        "suitcase" -> R.plurals.walking_aid_object_suitcase
        "frisbee" -> R.plurals.walking_aid_object_frisbee
        "skis" -> R.plurals.walking_aid_object_skis
        "snowboard" -> R.plurals.walking_aid_object_snowboard
        "sports ball" -> R.plurals.walking_aid_object_sports_ball
        "kite" -> R.plurals.walking_aid_object_kite
        "baseball bat" -> R.plurals.walking_aid_object_baseball_bat
        "baseball glove" -> R.plurals.walking_aid_object_baseball_glove
        "skateboard" -> R.plurals.walking_aid_object_skateboard
        "surfboard" -> R.plurals.walking_aid_object_surfboard
        "tennis racket" -> R.plurals.walking_aid_object_tennis_racket
        "bottle" -> R.plurals.walking_aid_object_bottle
        "wine glass" -> R.plurals.walking_aid_object_wine_glass
        "cup" -> R.plurals.walking_aid_object_cup
        "fork" -> R.plurals.walking_aid_object_fork
        "knife" -> R.plurals.walking_aid_object_knife
        "spoon" -> R.plurals.walking_aid_object_spoon
        "bowl" -> R.plurals.walking_aid_object_bowl
        "banana" -> R.plurals.walking_aid_object_banana
        "apple" -> R.plurals.walking_aid_object_apple
        "sandwich" -> R.plurals.walking_aid_object_sandwich
        "orange" -> R.plurals.walking_aid_object_orange
        "broccoli" -> R.plurals.walking_aid_object_broccoli
        "carrot" -> R.plurals.walking_aid_object_carrot
        "hot dog" -> R.plurals.walking_aid_object_hot_dog
        "pizza" -> R.plurals.walking_aid_object_pizza
        "donut" -> R.plurals.walking_aid_object_donut
        "cake" -> R.plurals.walking_aid_object_cake
        "chair" -> R.plurals.walking_aid_object_chair
        "couch" -> R.plurals.walking_aid_object_couch
        "potted plant" -> R.plurals.walking_aid_object_potted_plant
        "bed" -> R.plurals.walking_aid_object_bed
        "dining table" -> R.plurals.walking_aid_object_dining_table
        "toilet" -> R.plurals.walking_aid_object_toilet
        "tv" -> R.plurals.walking_aid_object_tv
        "laptop" -> R.plurals.walking_aid_object_laptop
        "mouse" -> R.plurals.walking_aid_object_mouse
        "remote" -> R.plurals.walking_aid_object_remote
        "keyboard" -> R.plurals.walking_aid_object_keyboard
        "cell phone" -> R.plurals.walking_aid_object_cell_phone
        "microwave" -> R.plurals.walking_aid_object_microwave
        "oven" -> R.plurals.walking_aid_object_oven
        "toaster" -> R.plurals.walking_aid_object_toaster
        "sink" -> R.plurals.walking_aid_object_sink
        "refrigerator" -> R.plurals.walking_aid_object_refrigerator
        "book" -> R.plurals.walking_aid_object_book
        "clock" -> R.plurals.walking_aid_object_clock
        "vase" -> R.plurals.walking_aid_object_vase
        "scissors" -> R.plurals.walking_aid_object_scissors
        "teddy bear" -> R.plurals.walking_aid_object_teddy_bear
        "hair drier" -> R.plurals.walking_aid_object_hair_drier
        "toothbrush" -> R.plurals.walking_aid_object_toothbrush
        else -> R.plurals.walking_aid_object_unknown
    }
}
