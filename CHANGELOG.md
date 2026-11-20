# Changelog

## 2.9.0

**Widget de carregamento**
- A % da bateria agora acompanha a carga de verdade, com duas casas (ex.: 61,40%). Antes ficava parada em 47%.
- Gráfico do começo ao fim da carga: da esquerda (0% de bateria) à direita (100%), com a bolinha andando junto com a %.
- O widget fica na tela até você tocar em **Fechar**. Pausas do carregador no meio viram uma carga só.
- Mais leve: os números são atualizados a cada poucos segundos (antes redesenhavam o carro várias vezes por segundo, o que podia deixar o papel de parede preto).
- No app, o widget aparece no tamanho dele e o painel de Portas se ajusta embaixo.

**Chuva**
- A chuva agora fica sempre se mexendo, sem pesar: só os pingos são desenhados por cima da imagem do carro.
- Quando a previsão disser chuva, o app pergunta **"Está chovendo aí?"**: *Chover agora* ou *Adiar chuva* (1 hora).
- Botão **Suspender chuva** no app e no papel de parede (só aparece chovendo).

**Papel de parede**
- Botões **◀ ● ▶** para mover o carro para os lados (vale no Fixo e no Auto).

**Atualizações (nova aba)**
- **Ajustes → Atualizações**: o app consulta este GitHub, baixa a versão nova e se instala sozinho, sem outro programa.
- Com "Buscar atualizações automaticamente" ligado, ao abrir o app ele avisa se há versão nova.

**Idiomas e lado do volante**
- O app segue o idioma do carro: português, inglês, espanhol, francês, russo, árabe, tailandês, vietnamita, indonésio e chinês.
- Em países com volante à direita, os painéis trocam de lado. Em árabe, a tela fica da direita para a esquerda.
- Escolha manual em **Ajustes → Idioma e região**.

**Visual do carro**
- Sombra nova embaixo do carro, no formato dele.
- Lanternas traseiras sem o vermelho vazando para a lataria.
- Fechado o furo do gancho de reboque no para-choque traseiro.

**Sinais do carro**
- DRL corrigido: o sinal usado antes era o do cinto do motorista.
## 2.8.3

**Widget de carregamento (novo)**
- Ao começar a carregar, aparece no lugar da temperatura: gráfico da potência entrando, % da bateria, potência, tensão, corrente e tempo restante.
- Calcula quanto a carga está custando a partir do valor do kWh que você escolhe.
- No fim, mostra o resumo da última carga (energia, duração e custo) e os km rodados desde a última carga.
- Nova aba **Ajustes → Carregamento**.

**Clima e saudações**
- Nome da sua cidade embaixo da temperatura. Sem internet, fica a última cidade conhecida.
- Previsão do tempo da semana: o céu segue a previsão mesmo sem internet.
- Segunda saudação com a data ("Hoje é qua. 18 de nov").
- Os tempos das saudações e da volta à paisagem agora são escolhidos em minutos e segundos.

**Papel de parede**
- Ativar e desativar com um toque no botão da tela principal.
- Botão **X** para esconder na hora os pontos e os botões.

**Visual do carro**
- Carro mais limpo no modo paisagem, com uma sombra suave no lago no lugar do reflexo (mais leve para a central).
- Vidros escuros: com o carro fechado, não dá mais para ver o interior.
- Lanternas traseiras fumê, sem o cromado.
- No modo estrada, as faixas fazem a curva junto com o volante.
- Corrigidos: as rodas virando para o lado contrário, pneus aparecendo no chão da estrada e o buraco nas rodas.

**Desempenho e estabilidade**
- Mais fluido andando: corrigida a causa principal das travadas.
- Se a central ficar lenta, a qualidade se ajusta sozinha.
- Se algo falhar, o app se corrige sozinho em vez de fechar.

Ao abrir pela primeira vez, **permita o acesso à localização**: é com ela que o app mostra a sua cidade, o clima e o horário do sol.
