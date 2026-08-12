# Compilação Android

1. Instale Android Studio atual, SDK 35 e JDK 17.
2. Abra a pasta `android`.
3. Em `android/app/build.gradle.kts`, altere `API_BASE_URL` para o endereço HTTPS público do painel.
4. Para desenvolvimento local no emulador, `http://10.0.2.2:8080` já está autorizado. Não autorize HTTP para domínios públicos.
5. Sincronize o Gradle e execute `app` em celular, Android TV ou TV Box compatível.

Para gerar um APK de teste: `gradlew assembleDebug`. O arquivo será criado em `android/app/build/outputs/apk/debug/app-debug.apk`.

Para produção, gere uma chave no Android Studio, mantenha-a fora do repositório e crie um Android App Bundle assinado. Antes da Play Store, forneça política de privacidade, tela de suporte, declaração de uso de dados e evidências de que o aplicativo não inclui ou promove conteúdo não autorizado.

## Uso

- Clique em um item para reproduzir.
- Pressione e segure (ou mantenha OK pressionado) para favoritar.
- Use os filtros Live, Filmes e Séries. A classificação depende dos metadados `group-title` da lista.
- Quando a lista tiver XMLTV e `tvg-id` correspondente, o programa atual aparece no cartão.

O DNS deve ser configurado pelo usuário nas opções normais do Android ou do roteador. O LPSM não altera DNS, não inclui WARP/VPN e não tenta evadir medidas regulatórias.
