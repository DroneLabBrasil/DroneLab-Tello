# DroneLab Tello 2.5.2 — prévia

- Liga os motores sem decolar pelo gesto esquerdo ↘ + direito ↙, na tela ou no gamepad.
- Reconhecimento da diagonal independente da velocidade selecionada; envio dos extremos CSC pelo protocolo nativo, sem comando de decolagem.
- Bloqueio em voo, com sensores/telemetria inválidos, bateria ≤20% ou calibração ativa. Envio por até 3 segundos, interrompido ao soltar, perder comunicação/foco ou solicitar pouso/emergência.
- Confirmação por telemetria de motores ativos no solo; o prazo nunca declara sucesso.
- Assinatura mantida para atualização sobre as distribuições 2.5 e 2.5.1.

O acionamento físico ainda precisa ser validado no Tello. Testes locais verificam o pacote CSC, bloqueios, prazo, liberação e ausência de comando de decolagem.

# DroneLab Tello 2.5.1 — prévia

Adaptação do TelloNO com interface DroneLab em português, menu de calibrações, guia IMU, acompanhamento CG, flips e reconexão na mesma tela.

Na 2.5.1: títulos da tela inicial centralizados, aviso fixo de conexão removido, flips habilitados com verificações de estado/bateria e reconexão após retorno da comunicação/Wi-Fi.

Validação: compilação Android, testes Java, simulação UDP e reprodução de captura CG. Voo/pouso/câmera da 2.5 foram confirmados em A54/Android 16; as mudanças da 2.5.1 e as calibrações implementadas ainda aguardam confirmação física adicional.

As distribuições 2.5 e 2.5.1 do mantenedor usam a mesma assinatura. Compilações locais podem usar outra chave; não incluem a chave privada do mantenedor.
