# EX2 Live 3D

Papel de parede e app 3D do **Geely EX2** para a central multimídia do carro (IHU629G, Android 9).

O carro em 3D na tela inicial acompanha o carro de verdade: portas, setas, faróis, freio, volante e velocidade. Tem cenário de lago e montanhas que muda com a hora e o clima, cenário de estrada quando o carro anda, temperatura externa, saudações, widget de carregamento e 10 idiomas (os mesmos da central).

## Como instalar

1. Baixe o arquivo `.apk` da versão mais nova em **[Releases](../../releases/latest)**.
2. Instale na central do carro (por pen drive ou pelo instalador de APK que você já usa).
3. Abra o **EX2 Live 3D** e permita o acesso à localização (cidade, clima e horário do sol).
4. Toque em **Ativar papel de parede**.

A instalação por cima mantém todas as suas configurações. A partir da 2.9.0, o próprio app avisa e instala as versões novas (**Ajustes → Atualizações**).

## Novidades

Veja o [CHANGELOG](CHANGELOG.md).

---

## Código aberto

O código é aberto para fortalecer a comunidade de donos do EX2. Licença **GPL-3.0** ([LICENSE](LICENSE)): use, estude e melhore à vontade; quem distribuir uma versão modificada precisa abrir o código também.

### O que tem aqui

| Pasta | O que é |
|---|---|
| `ex2-native/` | O app (Kotlin + [Filament](https://github.com/google/filament) 1.77.3). |
| `ex2-native/app/src/main/kotlin/br/com/ex2/live3d/` | Código: `World` (cena 3D), `Viewport3D` (tela/papel de parede), `CarBridge` (sinais do carro pelo VHAL), `Charge` (widget de carga), `Lang` (idiomas), `Updater` (atualização pelo GitHub)… |
| `ex2-native/tools/` | Scripts Python: preparar o modelo, ícones, traduções (`make_i18n.py` a partir de `tools/i18n/*.txt`). |
| `ex2-3d/tools/export-car.js` | Exporta o modelo 3D do carro a partir do configurador web. |
| `docs/sinais-EX2-catalogo.txt` | **Catálogo de sinais do VHAL do EX2**: portas, luzes, marcha, volante, freio, temperatura, carga, bateria… com os nomes oficiais (seção 9). Útil para qualquer app da central. |

### O que NÃO está aqui (e por quê)

- **Modelo 3D do carro** (`car.glb`, `interior.glb`, `car-mirror.glb`, `hubcap.glb`): vem do configurador do site da Geely, e os direitos são dela. Cada um gera o seu (abaixo).
- **Fontes** `FE-FONT.TTF` (placa Mercosul) e `falena-semi-bold.ttf`: licenças de terceiros. Coloque em `app/src/main/assets/` (a Falena em `assets/fonts/`), ou troque por outra fonte.
- **Chaves de assinatura**: o app precisa ser assinado com a **chave de plataforma** da central (a IHU629G usa a chave pública de teste do AOSP, SHA-256 `c8a2e9bc…92ab8`). Baixe `platform.pk8` e `platform.x509.pem` do AOSP (`build/target/product/security/`) e ponha em `ex2-native/keys/` (ou aponte a variável `EX2_KEYS`). É ela que dá ao app o acesso ao VHAL, a troca do papel de parede e a instalação das atualizações.

### Como compilar

1. Android Studio (o JBR dele), Android SDK com build-tools 36 e Gradle 9.8.2.
2. Gere o modelo: abra o configurador do EX2 no navegador, rode `ex2-3d/tools/export-car.js` no console com `ex2-native/tools/receive.py` aberto (porta 8799) e depois `python ex2-native/tools/prepare_assets.py` e `make_light_car.py`.
3. `cd ex2-native` e `.\build.ps1` (o APK sai em `ex2-native/dist/`). `.\build.ps1 -Emu` inclui x86_64 para o emulador (`emu.ps1`).

Contribuições são bem-vindas: abra uma *issue* ou um *pull request*. Ao mexer em sinais do carro, atualize o catálogo.

Aplicativo de distribuição gratuita, feito para os donos do Geely EX2. Se você pagou por ele, foi enganado.
Projeto independente, sem vínculo com a Geely.
