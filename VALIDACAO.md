# Validação 2.5.1

- Compilação Android: BUILD SUCCESSFUL.
- Assinatura validada e comparada à 2.5: mesmo certificado e pacote; versionCode 8, antes 7.
- Reprodução da captura real do CG: 4.597 pacotes binários válidos; reconhecimento após 15.087 ms.
- Simulação de CG e pouso: sem regressões.
- ReconnectFlipTest: bateria baixa bloqueia flip; direções f/l/b/r e tipo 0x70 corretos; perda de comunicação aciona nova negociação; retorno da telemetria; comandos e movimentos antigos descartados.
- Tela inicial revisada no código e recursos compilados. Não foi renderizada em um dispositivo/emulador nesta execução.
- Mudança de vínculo Wi-Fi usa NetworkCallback e tentativa periódica na Activity. A reconexão real após troca de bateria ainda depende do teste no A54.
