# DroneLab Tello

Aplicativo Android para controlar o Ryze Tello, derivado do [TelloNO](https://github.com/Monks103/TelloNO), com interface em português, identidade DroneLab e calibrações no menu da engrenagem.

**Versão 2.5.2 — prévia de testes.** Voo, pouso, câmera e telemetria da 2.5 foram confirmados em Galaxy A54 com Android 16. Flips e reconexão da 2.5.1 foram testados em simulação e ainda precisam de verificação física.

## Recursos

- Controle por direcionais na tela e gamepad, com neutralização ao sair da tela/perder foco.
- Vídeo por Android MediaCodec, fotos e gravação MP4.
- Telemetria de bateria e altitude.
- Engrenagem com IMU habilitada no solo e centro de gravidade habilitado em voo; o envio CG exige estado pairado e validações adicionais.
- Guia visual manual das seis posições da IMU.
- Acompanhamento do CG com base em mensagens do drone; sem declarar sucesso por temporizador ou apenas por ACK.
- Flips com bateria mínima de 50%, estado de voo válido e nenhuma calibração ativa.
- Reconexão na mesma tela após retorno do Wi-Fi, descartando ações e direcionais antigos. Não há retomada automática de decolagem, manobra ou calibração.

## Ligar motores sem decolar (2.5.2)

Com o drone no solo, mantenha simultaneamente o stick esquerdo para baixo/dentro (↘) e o direito para baixo/dentro (↙). O gesto é reconhecido antes da escala de velocidade, tanto na tela como em um gamepad. Não misture um stick da tela com outro do gamepad. Solte os sticks quando a telemetria confirmar os motores ligados.

O app envia somente a combinação nativa CSC, sem comando de decolagem. A tentativa dura no máximo 3 segundos e exige telemetria recente, motores parados, bateria acima de 20%, sensores sem erros e nenhuma calibração. Em voo o gesto é bloqueado e os controles são neutralizados enquanto ele estiver mantido. Soltar o gesto, perder foco, suspender o app, perder comunicação ou solicitar pouso/emergência interrompe o envio. Não há repetição automática após interrupção: solte os sticks antes de tentar novamente. A confirmação significa que a telemetria informou motores ativos no solo, não que o drone decolou.

A função ainda exige teste físico nesta versão. Após ligar os motores, acionar o acelerador pode iniciar voo manual; mantenha-o neutro se desejar apenas os motores girando. Para encerrar normalmente use Pousar. O botão de parada de motores continua sendo de emergência, com confirmação.

Referência de protocolo: [bluejune, descoberta da sequência CSC](https://tellopilots.com/threads/as-yet-undiscovered-commands.2344/). A combinação usa os eixos roll/pitch/throttle em 364 e yaw em 1684, com fastmode desligado.

## Limites conhecidos

A lógica do CG foi identificada em uma captura de operação concluída no aplicativo original, firmware 01.04.91.01. Outros códigos de falha não foram mapeados em testes físicos. O comportamento em outros firmwares não está validado.

A reconexão depende de o Android voltar ao Wi-Fi do Tello. Uma gravação em andamento é finalizada quando a prévia é interrompida e não reinicia sozinha. A seleção de posições do guia IMU é manual, não reconhecimento automático das faces.

Teste mudanças no controle primeiro no solo; manobras exigem espaço livre. Esta prévia não implica equivalência completa ou certificação em relação ao aplicativo original.

## Compilar

Use Android Studio, JDK 17 e Android SDK Platform 34. Abra a pasta que contém settings.gradle e execute:

```sh
./gradlew assembleDebug
```

No Windows, use `.\gradlew.bat assembleDebug`. O APK é gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Sem configuração adicional, o Android Gradle Plugin gera uma assinatura debug local para o desenvolvedor. Esse APK local não substitui um APK assinado com outra chave.

## Assinatura do mantenedor

Nenhuma chave privada ou captura de rede é distribuída neste repositório. Para reproduzir a assinatura de uma distribuição do mantenedor, mantenha a chave fora do repositório e configure estas variáveis somente no ambiente local ou nos Secrets do processo de build:

- DRONELAB_KEYSTORE: caminho do arquivo privado.
- DRONELAB_STORE_PASSWORD
- DRONELAB_KEY_ALIAS
- DRONELAB_KEY_PASSWORD

Não publique as variáveis, seus valores, a chave ou os arquivos de captura. O arquivo .gitignore inclui essas exclusões. A chave usada em distribuições de teste anteriores permanece apenas no projeto privado do mantenedor.

## Testes

Os testes Java independentes estão em tests/nativeflight e tests/CalibrationProtocolTest.java. Cobrem protocolo, verificação CRC, reprodução de sequência CG, controles neutros, conexão simulada, flips e reconexão. O teste de reprodução recebe o caminho de uma captura autorizada como argumento; o arquivo privado não faz parte do repositório.

## Créditos e licença

- [TelloNO / MusanaINC](https://github.com/Monks103/TelloNO): base do aplicativo, MIT. Aviso original preservado em LICENSE.
- [TelloPy](https://github.com/hanyazou/TelloPy): referência de protocolo e fotografias das posições IMU, Apache-2.0.
- [SMerrony/tello](https://github.com/SMerrony/tello): referência adicional do protocolo, MIT.

Licenças e atribuições adicionais estão em third_party e app/src/main/assets/licenses. As alterações Java são implementações independentes a partir dessas referências e de comunicação observada com um Tello. O APK original protegido não é redistribuído.

DroneLab Tello é uma adaptação independente; não há endosso implícito dos autores originais, Ryze ou DJI.
