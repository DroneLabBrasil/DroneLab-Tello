# Testes locais do transporte UDP

Requer Java JDK 17 ou posterior, com `java` e `javac` no PATH. Não requer Android SDK nem drone. Feche outros programas que estejam usando as portas UDP locais 8889 e 8890.

No Linux/macOS, a partir da raiz do projeto:

```sh
sh tests/transport/run.sh
```

No PowerShell do Windows, a partir da raiz do projeto:

```powershell
.\tests\transport\run.ps1
```

O teste compila os arquivos reais `UDPClient.java` e `UDPStatusServer.java` com substitutos mínimos de `android.net.Network`, `android.util.Log` e `android.os.SystemClock`. Esses substitutos estão apenas nesta pasta de testes e não são incluídos no aplicativo. A verificação redireciona o destino interno para loopback usando reflexão, sem alterar os arquivos de produção.

Verifica envio de movimento, envio de neutro ao soltar os controles, 10.000 atualizações RC sem bloqueio da interface chamadora, neutro ao suspender/fechar, rejeição de telemetria inválida, aceitação de telemetria válida e expiração de telemetria mesmo com tráfego inválido contínuo.

Os testes usam sockets UDP reais no computador. Não verificam o vínculo à rede Wi-Fi no Android, o A54, o comportamento do drone, a câmera ou a calibração. O resultado esperado são duas linhas `PASS`. Os arquivos compilados ficam em pasta temporária removida no final.
