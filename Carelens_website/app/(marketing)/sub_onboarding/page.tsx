import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SiteHeader } from "@/components/sections/site-header";
import { SiteFooter } from "@/components/sections/site-footer";
import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";
import { OnboardingQuestionCard } from "@/components/onboarding/onboarding-question-card";
import { OnboardingFormWrapper } from "@/components/onboarding/onboarding-form-wrapper";
import { OnboardingCta } from "@/components/onboarding/onboarding-cta";
import { getSessionUserProfileById, parseUserIdCookie } from "@/lib/session-user";

const onboardingQuestions = [
  {
    id: "vision-level",
    question: "Como está a visão da pessoa idosa atualmente?",
    options: ["Boa (enxerga bem)", "Moderada (usa óculos)", "Precária (dificuldade significativa)"],
  },
  {
    id: "daily-routine",
    question: "Qual a rotina mais importante para monitorar?",
    options: [
      "Medicamentos e lembretes de saúde",
      "Segurança no deslocamento e mobilidade",
      "Companhia e assistência no dia a dia",
      "Todos os acima",
    ],
  },
  {
    id: "tech-comfort",
    question: "A pessoa idosa tem familiaridade com tecnologia?",
    options: [
      "Sim, usa smartphone/tablet com facilidade",
      "Pouca experiência, mas está disposta a aprender",
      "Nenhuma experiência com tecnologia",
    ],
  },
  {
    id: "living-situation",
    question: "Como é a situação de moradia da pessoa idosa?",
    options: [
      "Mora sozinha",
      "Mora com familiares",
      "Residência assistida ou casa de repouso",
    ],
  },
  {
    id: "primary-concern",
    question: "Qual a principal preocupação com o idoso?",
    options: [
      "Quedas e acidentes domésticos",
      "Esquecimento e confusão mental",
      "Isolamento e solidão",
      "Adesão a medicamentos",
    ],
  },
];

type OnboardingPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function OnboardingPage({ searchParams }: OnboardingPageProps) {
  const params = await searchParams;
  const cookieStore = await cookies();
  const userId = parseUserIdCookie(cookieStore.get("carelens_user_id")?.value);

  if (!userId) {
    redirect("/signin?auth=required&next=/sub_onboarding");
  }

  const userProfile = await getSessionUserProfileById(userId);
  if (!userProfile) {
    redirect("/signin?auth=required&next=/sub_onboarding");
  }

  const selectedAnswers = Object.fromEntries(
    onboardingQuestions.map((item) => {
      const explicitValue = String(params[item.id] ?? "");

      if (explicitValue) {
        return [item.id, explicitValue];
      }

      if (item.id === "vision-level") {
        return [item.id, userProfile.visionLevel ?? ""];
      }
      if (item.id === "tech-comfort") {
        return [item.id, userProfile.techComfortLevel ?? ""];
      }
      if (item.id === "living-situation") {
        return [item.id, userProfile.livingSituation ?? ""];
      }
      return [item.id, ""];
    }),
  );
  const fullName = String(params.fullName ?? userProfile.fullName ?? "");
  const email = String(params.email ?? userProfile.email ?? "");
  const sex = String(params.sex ?? userProfile.sex ?? "");
  const healthCondition = String(params.healthCondition ?? userProfile.healthCondition ?? "");
  const dateOfBirth = String(
    params.dateOfBirth ?? (userProfile.dateOfBirth ? userProfile.dateOfBirth.toISOString().slice(0, 10) : ""),
  );
  const medications = String(params.medications ?? "");
  const allergies = String(params.allergies ?? "");
  const emergencyName = String(params.emergencyName ?? "");
  const emergencyPhone = String(params.emergencyPhone ?? "");
  const glassesColor = String(params.glassesColor ?? "");

  return (
    <div className="pb-10">
      <div className="carelens-ambient" aria-hidden="true" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-3xl">
          <GlassCard className="glass-card-strong p-6 sm:p-8">
            <div className="onboarding-progress animate-in">
              <p className="onboarding-progress-step">Passo 1 de 2</p>
              <div className="onboarding-progress-bar">
                <div className="onboarding-progress-fill" style={{ width: "50%" }} />
              </div>
            </div>
            <SectionHeading
              eyebrow="Óculos + Assinatura"
              title="Perguntas essenciais para configurar a IA"
              subtitle="Pedimos só o essencial para personalizar a experiência do seu familiar idoso. Em seguida, você finaliza a compra dos óculos e ativa a assinatura."
              className="animate-in"
            />

            <OnboardingFormWrapper>
              <form className="mt-6 space-y-4" action="/coupon" method="get">
                {onboardingQuestions.map((item, index) => (
                  <div key={item.id} className="animate-in">
                    <OnboardingQuestionCard
                      question={item.question}
                      questionNumber={index + 1}
                      options={item.options}
                      name={item.id}
                      selectedValue={selectedAnswers[item.id]}
                    />
                  </div>
                ))}

                <div className="onboarding-data-card animate-in">
                  <p className="onboarding-data-card-title">Dados para personalização</p>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">Nome completo</span>
                      <input
                        name="fullName"
                        required
                        defaultValue={fullName}
                        placeholder="Nome do responsável"
                        className="onboarding-field-input"
                      />
                    </label>

                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">E-mail</span>
                      <input
                        name="email"
                        type="email"
                        required
                        defaultValue={email}
                        placeholder="seuemail@exemplo.com"
                        className="onboarding-field-input"
                      />
                    </label>

                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">Sexo</span>
                      <select
                        name="sex"
                        required
                        defaultValue={sex}
                        className="onboarding-field-select"
                      >
                        <option value="" disabled>
                          Selecione
                        </option>
                        <option value="MALE">Masculino</option>
                        <option value="FEMALE">Feminino</option>
                        <option value="PREFER_NOT_SAY">Prefiro não dizer</option>
                      </select>
                    </label>

                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">Alguma condição de saúde? Se sim, qual?</span>
                      <input
                        name="healthCondition"
                        defaultValue={healthCondition}
                        placeholder="Ex.: hipertensão, diabetes, Alzheimer, etc."
                        className="onboarding-field-input"
                      />
                    </label>

                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">Medicamentos em uso</span>
                      <input
                        name="medications"
                        defaultValue={medications}
                        placeholder="Ex.: Losartana, Metformina, etc."
                        className="onboarding-field-input"
                      />
                    </label>

                    <label className="sm:col-span-2">
                      <span className="onboarding-field-label">Alergias conhecidas</span>
                      <input
                        name="allergies"
                        defaultValue={allergies}
                        placeholder="Ex.: dipirona, látex, etc."
                        className="onboarding-field-input"
                      />
                    </label>

                    <label>
                      <span className="onboarding-field-label">Contato de emergência — Nome</span>
                      <input
                        name="emergencyName"
                        defaultValue={emergencyName}
                        placeholder="Nome do contato"
                        className="onboarding-field-input"
                      />
                    </label>

                    <label>
                      <span className="onboarding-field-label">Contato de emergência — Telefone</span>
                      <input
                        name="emergencyPhone"
                        defaultValue={emergencyPhone}
                        placeholder="(11) 99999-9999"
                        className="onboarding-field-input"
                      />
                    </label>

                    <label>
                      <span className="onboarding-field-label">Cor do óculos</span>
                      <select
                        name="glassesColor"
                        defaultValue={glassesColor}
                        className="onboarding-field-select"
                      >
                        <option value="" disabled>
                          Selecione
                        </option>
                        <option value="PRETO">Preto</option>
                        <option value="BRANCO">Branco</option>
                      </select>
                    </label>
                  </div>
                </div>
                <input type="hidden" name="purchaseType" value="glasses_subscription" />
                <input type="hidden" name="dateOfBirth" value={dateOfBirth} />

                <div className="mt-4 flex justify-center animate-in">
                  <OnboardingCta type="submit">
                    Continuar
                  </OnboardingCta>
                </div>
              </form>
            </OnboardingFormWrapper>
          </GlassCard>
        </section>

        <div className="mt-8 flex justify-center animate-in">
          <Link href="/" className="onboarding-cta onboarding-cta-secondary">
            Voltar para início
          </Link>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
