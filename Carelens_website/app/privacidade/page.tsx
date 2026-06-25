import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

/**
 * Privacy Policy (LGPD-compliant, Portuguese-BR).
 *
 * This is a pragmatic baseline. Review by legal counsel is recommended
 * before production launch to ensure full regulatory alignment.
 */
export default function PrivacyPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Privacidade</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Política de Privacidade
            </h1>
            <p className="mt-2 text-xs text-slate-500">
              Última atualização: maio de 2026
            </p>
          </GlassCard>

          {/* 1. Controlador / Contato */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              1. Controlador e Canal de Contato
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                <strong>Controlador:</strong> CareLens AI Tecnologia Ltda.
              </p>
              <p>
                <strong>E-mail:</strong> contato@carelens.com.br
              </p>
              <p>
                <strong>Suporte:</strong> contato@carelens.com.br
              </p>
              <p>
                A CareLens AI é a controladora dos dados pessoais tratados no âmbito
                desta plataforma. Para exercer seus direitos como titular,
                entre em contato pelo e-mail acima ou pela seção{" "}
                <strong>&ldquo;Seus Direitos&rdquo;</strong> abaixo.
              </p>
              <p>
                <strong>E-mail:</strong> privacidade@carelens.com.br
              </p>
              <p>
                <strong>Suporte:</strong> suporte@carelens.com.br
              </p>
              <p>
                A CareLens é a controladora dos dados pessoais tratados no âmbito
                desta plataforma. Para exercer seus direitos como titular,
                entre em contato pelo e-mail acima ou pela seção{" "}
                <strong>&ldquo;Seus Direitos&rdquo;</strong> abaixo.
              </p>
            </div>
          </GlassCard>

          {/* 2. Quais Dados Coletamos */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              2. Quais Dados Pessoais Coletamos
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Podemos coletar as seguintes categorias de dados pessoais:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Dados de identificação e contato:</strong> nome
                  completo, e-mail, CPF, data de nascimento, telefone.
                </li>
                <li>
                  <strong>Dados do perfil do idoso:</strong> informações sobre
                  visão, audição, mobilidade, medicamentos em uso e contatos
                  de emergência compartilhados voluntariamente durante o
                  onboarding.
                </li>
                <li>
                  <strong>Dados de pagamento:</strong> informações de
                  transação (bandeira, valor, parcelas). A CareLens AI{" "}
                  <strong>não armazena</strong> números completos de cartão,
                  CVV ou dados sensíveis de pagamento — o processamento é
                  terceirizado ao Asaas/Stripe.
                </li>
                <li>
                  <strong>Dados de uso do dispositivo:</strong> interações com
                  os óculos inteligentes, preferências de assistência,
                  histórico de comandos de voz.
                </li>
                <li>
                  <strong>Dados de navegação:</strong> endereço IP, tipo de
                  navegador, páginas acessadas, duração da sessão (coletados
                  de forma anonimizada quando possível).
                </li>
                <li>
                  <strong>Cookies essenciais:</strong> identificadores de
                  sessão (<code>carelens_session</code>,{" "}
                  <code>carelens_user</code>) necessários para o
                  funcionamento da plataforma.
                </li>
              </ul>
            </div>
          </GlassCard>

          {/* 3. Finalidades e Bases Legais */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              3. Finalidades e Bases Legais (LGPD)
            </h2>
            <div className="mt-3 space-y-3 text-sm text-slate-800 leading-relaxed">
              <p>Tratamos seus dados pessoais para as seguintes finalidades:</p>
              <table className="w-full mt-2 border-collapse text-xs sm:text-sm">
                <thead>
                  <tr className="border-b border-[#cdbe98]/60">
                    <th className="text-left py-2 pr-2 font-semibold">Finalidade</th>
                    <th className="text-left py-2 pl-2 font-semibold">Base Legal (LGPD)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#cdbe98]/40">
                  <tr>
                    <td className="py-2 pr-2 align-top">Criação e gestão de conta</td>
                    <td className="py-2 pl-2 align-top">Execução de contrato (art. 7º, V)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Processamento de pagamentos</td>
                    <td className="py-2 pl-2 align-top">Execução de contrato (art. 7º, V)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Configuração e personalização dos óculos inteligentes</td>
                    <td className="py-2 pl-2 align-top">Execução de contrato (art. 7º, V)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Comunicação operacional e suporte</td>
                    <td className="py-2 pl-2 align-top">Legítimo interesse (art. 7º, IX)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Cumprimento de obrigações legais (fiscais, contábeis)</td>
                    <td className="py-2 pl-2 align-top">Obrigação legal (art. 7º, II)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Coleta de dados do perfil do idoso (onboarding)</td>
                    <td className="py-2 pl-2 align-top">Consentimento (art. 7º, I e 11º, I)</td>
                  </tr>
                  <tr>
                    <td className="py-2 pr-2 align-top">Cookies não essenciais/analytics</td>
                    <td className="py-2 pl-2 align-top">Consentimento (art. 7º, I)</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </GlassCard>

          {/* 4. Compartilhamento */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              4. Compartilhamento de Dados
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Compartilhamos seus dados apenas quando necessário para a prestação dos serviços:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Asaas / Stripe:</strong> processadores de pagamento —
                  recebem dados de transação para executar cobranças e
                  reembolsos.
                </li>
                <li>
                  <strong>HeyCyan (fabricante dos óculos):</strong> recebe
                  dados técnicos limitados para suporte e garantia do
                  dispositivo.
                </li>
                <li>
                  <strong>Prestadores de serviços de IA:</strong> processam
                  comandos de voz e interações para fornecer assistência
                  personalizada.
                </li>
                <li>
                  <strong>Autoridades legais:</strong> quando exigido por lei
                  ou ordem judicial.
                </li>
              </ul>
              <p className="mt-3">
                Não vendemos dados pessoais a terceiros. Todos os parceiros
                são contratualmente obrigados a tratar dados conforme a LGPD.
              </p>
            </div>
          </GlassCard>

          {/* 5. Armazenamento e Retenção */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              5. Armazenamento e Retenção
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Seus dados são armazenados em servidores seguros no Brasil
                (via provedor de nuvem). Mantemos seus dados pessoais pelo
                tempo necessário para cumprir as finalidades descritas nesta
                política, respeitando os prazos legais aplicáveis:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Dados de conta e contrato:</strong> enquanto a
                  relação contratual estiver vigente + 5 anos após o término
                  (prazo prescricional).
                </li>
                <li>
                  <strong>Dados do perfil do idoso (onboarding):</strong> 5 anos após o
                  último contato, salvo retenção legal superior.
                </li>
                <li>
                  <strong>Dados de pagamento e transação:</strong> 5 anos para
                  cumprimento de obrigações fiscais e contábeis.
                </li>
                <li>
                  <strong>Dados de navegação e cookies:</strong> conforme
                  política de cookies, com consentimento.
                </li>
              </ul>
              <p className="mt-2">
                Após o período de retenção, os dados são eliminados ou
                anonimizados. Para detalhes completos, consulte nossa{" "}
                <a href="/privacidade/dados" className="text-brand underline">
                  Política de Retenção e Eliminação de Dados
                </a>.
              </p>
            </div>
          </GlassCard>

          {/* 6. Segurança */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              6. Segurança dos Dados
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Adotamos medidas técnicas e organizacionais para proteger seus dados pessoais:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Criptografia em trânsito (TLS 1.3) para todas as comunicações.</li>
                <li>Armazenamento com criptografia em repouso.</li>
                <li>Controle de acesso baseado em funções (RBAC).</li>
                <li>Auditoria de eventos críticos (webhooks, alterações de status).</li>
                <li>Nunca armazenamos números completos de cartão, CVV ou senhas em texto plano.</li>
                <li>Revisões periódicas de segurança e conformidade.</li>
              </ul>
            </div>
          </GlassCard>

          {/* 7. Direitos do Titular */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              7. Seus Direitos (Titular de Dados)
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Nos termos da LGPD (Lei nº 13.709/2018), você possui os
                seguintes direitos:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Confirmação e acesso:</strong> saber se tratamos seus
                  dados e obter cópia.
                </li>
                <li>
                  <strong>Correção:</strong> solicitar a retificação de dados
                  incompletos, inexatos ou desatualizados.
                </li>
                <li>
                  <strong>Anonimização, bloqueio ou eliminação:</strong>
                  solicitar a anonimização, bloqueio ou eliminação de dados
                  desnecessários ou tratados em desconformidade com a lei.
                </li>
                <li>
                  <strong>Portabilidade:</strong> solicitar a portabilidade
                  dos dados a outro fornecedor de serviço, observados os
                  segredos comercial e industrial.
                </li>
                <li>
                  <strong>Eliminação com consentimento:</strong> revogar o
                  consentimento a qualquer tempo, com eliminação dos dados
                  coletados sob essa base legal.
                </li>
                <li>
                  <strong>Informação sobre compartilhamento:</strong> saber
                  com quais entidades públicas e privadas compartilhamos seus
                  dados.
                </li>
                <li>
                  <strong>Oposição:</strong> opor-se a tratamento realizado
                  com base em legítimo interesse.
                </li>
                <li>
                  <strong>Revisão de decisões automatizadas:</strong> solicitar
                  revisão de decisões tomadas unicamente com base em
                  tratamento automatizado de dados pessoais.
                </li>
              </ul>
            </div>
          </GlassCard>

          {/* 8. Canal para Solicitações */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              8. Canal para Solicitações LGPD
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Para exercer seus direitos, entre em contato pelo e-mail:
              </p>
              <p className="font-semibold text-brand">
                contato@carelens.com.br
              </p>
              <p className="mt-2">
                Responderemos em até <strong>15 dias úteis</strong>, conforme
                o prazo legal. Identifique-se com nome, e-mail cadastrado e
                descrição clara do direito que deseja exercer.
              </p>
              <p className="mt-2">
                Usuários logados também podem solicitar a exclusão da conta
                diretamente pela{" "}
                <a href="/account" className="text-brand underline">
                  página da conta
                </a>
                , na opção &ldquo;Excluir minha conta e dados&rdquo;.
              </p>
              <p className="mt-2">
                Você também pode contatar a Autoridade Nacional de Proteção de
                Dados (ANPD) caso entenda que seus direitos não foram
                adequadamente atendidos.
              </p>
            </div>
          </GlassCard>

          {/* 9. Alterações da Política */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              9. Alterações desta Política
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Esta política pode ser atualizada periodicamente para refletir
                mudanças em nossas práticas, na legislação ou em decisões
                regulatórias. Notificaremos alterações relevantes por e-mail
                e/ou aviso destacado na plataforma.
              </p>
              <p className="mt-2">
                Recomendamos revisar esta página periodicamente. O uso
                continuado da plataforma após alterações constitui aceitação
                da versão atualizada.
              </p>
              <p className="mt-4 text-xs text-slate-500">
                Esta política é um documento de conformidade preliminar e não
                substitui aconselhamento jurídico especializado.
              </p>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
