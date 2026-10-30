# Centro de gravidade: evidência e limites

Uma captura autorizada do aplicativo Tello original 1.6.4.0 registrou pedido 0x1054, tipo 0x68, payload 01 e ACK 00. Na telemetria 0x56, byte 22, bits 3–4, observou-se a sequência 0 -> 1 -> 2 -> 0 (valores completos 0, 8, 16, 0 na captura). O operador confirmou a mensagem de conclusão no aplicativo original. Firmware observado: 01.04.91.01.

A implementação exige pedido local, estado em andamento e depois conclusão. ACK, resultado antigo e timer não indicam sucesso. Estados desconhecidos e ausência de confirmação são tratados como conclusão não confirmada. A captura não está incluída. Não houve redistribuição do código protegido do aplicativo original.

Referência aberta independente para o envio CG: https://github.com/bgromov/TelloSwift

Ainda é necessário ampliar os testes físicos e mapear códigos de erro de outros cenários/firmwares.
