import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

/**
 * Data retention & deletion policy (LGPD).
 * Describes retention periods, deletion criteria, and anonymization rules.
 */
export default function DataRetentionPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Privacidade</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Política de Retenção e Eliminação de Dados
            </h1>
            <p className="mt-2 text-xs text-slate-500">
              Última atualização: maio de 2026
            </p>
          </GlassCard>

          {/* Introduction */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Introdução
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Esta política define os prazos de retenção e os critérios de
                eliminação dos dados pessoais tratados pela CareLens AI, em
                conformidade com a Lei Geral de Proteção de Dados (LGPD — Lei
                nº 13.709/2018).
              </p>
              <p>
                A CareLens AI armazena dados pessoais apenas pelo tempo necessário
                para cumprir as finalidades para as quais foram coletados,
                respeitando prazos legais e regulatórios aplicáveis.
              </p>
              <p>
                A CareLens armazena dados pessoais apenas pelo tempo necessário
                para cumprir as finalidades para as quais foram coletados,
                respeitando prazos legais e regulatórios aplicáveis.
              </p>
            </div>
          </GlassCard>

          {/* Retention Periods Table */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Prazos de Retenção
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <table className="w-full border-collapse text-xs sm:text-sm">
                <thead>
                  <tr className="border-b border-[#cdbe98]/60">
                    <th className="text-left py-2 pr-2 font-semibold">Categoria de Dados</th>
                    <th className="text-left py-2 px-2 font-semibold">Prazo de Retenção</th>
                    <th className="text-left py-2 pl-2 font-semibold">Justificativa</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#cdbe98]/40">
                  <tr>
                    <td className="py-2 pr-2 align-top">Perfil de usuário (nome, e-mail, dados cadastrais)</td>
                    <td className="py-2 px-2 align-top">Vigência do contrato + 5 anos</td>
                    <td className="py-2 pl-2 align-top">Execução contratual e prazo prescricional</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Perfil do idoso (onboarding)</td>
                    <td className="py-2 px-2 align-top">5 anos após último contato</td>
                    <td className="py-2 pl-2 align-top">Obrigação legal e prudencial (registro de assistência)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Dados de pagamento (transações)</td>
                    <td className="py-2 px-2 align-top">5 anos</td>
                    <td className="py-2 pl-2 align-top">Obrigações fiscais, contábeis e tributárias (art. 195 CTN)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Registros de assinatura (Subscription/AsaasSubscription)</td>
                    <td className="py-2 px-2 align-top">5 anos após cancelamento</td>
                    <td className="py-2 pl-2 align-top">Prazo prescricional para cobranças e contestações</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Interações com dispositivo (comandos de voz, preferências)</td>
                    <td className="py-2 px-2 align-top">2 anos após última interação</td>
                    <td className="py-2 pl-2 align-top">Melhoria do serviço e personalização da IA</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Webhooks e logs de evento</td>
                    <td className="py-2 px-2 align-top">2 anos</td>
                    <td className="py-2 pl-2 align-top">Auditoria, segurança e depuração</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Cookies de sessão (carelens_user_id, carelens_booking_id)</td>
                    <td className="py-2 px-2 align-top">Duração da sessão</td>
                    <td className="py-2 pl-2 align-top">Funcionamento essencial da plataforma</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Cookies não essenciais (analytics)</td>
                    <td className="py-2 px-2 align-top">Conforme aceito pelo usuário, no máximo 12 meses</td>
                    <td className="py-2 pl-2 align-top">Consentimento do titular</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </GlassCard>

          {/* Deletion Criteria */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Critérios de Eliminação
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Os dados pessoais são eliminados quando:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Solicitação do titular:</strong> mediante requisição
                  de exclusão pelo titular (desde que não haja obrigação legal
                  de retenção).
                </li>
                <li>
                  <strong>Término da finalidade:</strong> quando os dados não
                  forem mais necessários para a finalidade original.
                </li>
                <li>
                  <strong>Fim do prazo de retenção:</strong> expirado o prazo
                  máximo definido na tabela acima.
                </li>
                <li>
                  <strong>Revogação de consentimento:</strong> quando o
                  tratamento se baseava exclusivamente em consentimento e este
                  for revogado.
                </li>
                <li>
                  <strong>Determinação legal:</strong> por ordem de autoridade
                  competente.
                </li>
              </ul>
            </div>
          </GlassCard>

          {/* Anonymization */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Anonimização vs. Eliminação
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Quando a retenção for exigida por obrigação legal, mas os
                dados não forem mais necessários para a finalidade original, a
                CareLens poderá:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Anonimizar</strong> os dados (tornando impossível a
                  identificação do titular), mantendo-os para fins
                  estatísticos ou de cumprimento legal.
                </li>
                <li>
                  <strong>Eliminar</strong> os dados de forma segura quando
                  não houver qualquer obrigação de retenção.
                </li>
              </ul>
              <p className="mt-2">
                Dados anonimizados não são considerados dados pessoais para
                fins da LGPD.
              </p>
            </div>
          </GlassCard>

          {/* Operational Retention */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Retenção Operacional e Legal
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Determinados dados precisam ser retidos mesmo após a
                solicitação de exclusão pelo titular, nas seguintes hipóteses:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Obrigações fiscais:</strong> notas fiscais, registros
                  de transações e faturas devem ser retidos por 5 anos (CTN,
                  art. 195).
                </li>
                <li>
                  <strong>Regulamentações de saúde:</strong> prontuários e
                  registros clínicos podem ter prazos de retenção específicos
                  determinados pelo CFM/CRM.
                </li>
                <li>
                  <strong>Processos judiciais:</strong> dados envolvidos em
                  disputas legais são retidos até a conclusão do processo.
                </li>
              </ul>
              <p className="mt-2">
                Nesses casos, o acesso é restrito ao mínimo necessário para
                cumprimento da obrigação.
              </p>
            </div>
          </GlassCard>

          {/* Safe Deletion Process */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Processo de Eliminação Segura
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>A eliminação de dados segue os seguintes procedimentos:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Dados digitais:</strong> remoção dos registros do
                  banco de dados ativo com confirmação de exclusão.
                </li>
                <li>
                  <strong>Backups:</strong> dados em backup são sobrescritos
                  conforme o ciclo natural de rotação (máximo 90 dias).
                </li>
                <li>
                  <strong>Auditoria:</strong> o evento de exclusão é
                  registrado em log de auditoria interna.
                </li>
                <li>
                  <strong>Verificação:</strong> após a exclusão, é feita
                  verificação amostral para confirmar a remoção.
                </li>
              </ul>
            </div>
          </GlassCard>

          {/* How to Request */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              Como Solicitar a Exclusão
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Você pode solicitar a exclusão de seus dados de duas formas:</p>
              <ol className="list-decimal pl-5 space-y-1 mt-2">
                <li>
                  <strong>Automaticamente (usuários logados):</strong> acesse
                  sua{" "}
                  <a href="/account" className="text-brand underline">
                    página da conta
                  </a>{" "}
                  e clique em &ldquo;Excluir minha conta e dados&rdquo;.
                  Isso remove seus dados de perfil, pagamentos e assinaturas
                  da base ativa.
                </li>
                <li>
                  <strong>Por e-mail:</strong> envie sua solicitação para{" "}
                  <span className="font-semibold text-brand">
                    contato@carelens.com.br
                  </span>{" "}
                  com nome e e-mail cadastrado. Responderemos em até 15 dias úteis.
                </li>
              </ol>
            </div>
          </GlassCard>

          {/* Footer note */}
          <GlassCard className="p-6 sm:p-7">
            <p className="text-xs text-slate-500">
              Esta política é um documento de conformidade preliminar e não
              substitui aconselhamento jurídico especializado. Recomenda-se
              revisão por assessoria jurídica antes da publicação em produção.
            </p>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
