# Onde Estou para Android

Aplicação Android nativa em Kotlin e Jetpack Compose.

## Funcionalidades
- Localização no dispositivo com permissões Android.
- Conversão ETRS89 para PT-TM06, EPSG:3763.
- Identificação administrativa pela API OGC da DGT.
- Morada e código postal via OpenStreetMap Nominatim.
- Precisão GPS, cópia dos resultados e abertura no Google Maps.

## Gerar APK no GitHub
O workflow **Construir APK Android** é executado quando há alterações na pasta android ou manualmente em Actions.
O ficheiro gerado fica nos artefactos da execução, com o nome **Onde-Estou-APK**.
O APK de debug não está assinado para distribuição na Play Store. Pode ser instalado manualmente em dispositivos Android.

## Compilar localmente no Windows
Instalar Android Studio e JDK 17, abrir esta pasta android no Android Studio e executar a tarefa Gradle `:app:assembleDebug`.
