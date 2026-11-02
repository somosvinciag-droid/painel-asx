# Avisos de licença (NOTICE)

## Receptor de Android Auto: AGPLv3

O receptor de Android Auto do Painel ASX (arquivos `app/src/main/java/br/com/leonardo/painelasx/Aa*.kt`
e `AndroidAuto.kt`) foi escrito adaptando o desenho, as regras do protocolo e os formatos de mensagem do
**Headunit Revived / Open Headunit**:

- https://github.com/andreknieriem/headunit-revived (Kotlin), licença **GNU Affero General Public License v3**.
- Copyright (C) 2011-2015 Michael A. Reid (Headunit original, AGPLv3) e os contribuidores do Headunit Revived
  (André Rinas e outros).

Por isso esses arquivos são distribuídos sob a **GNU Affero General Public License, versão 3 ou posterior**
(texto completo em `LICENSE-AGPL-3.0.txt`). Como eles fazem parte do mesmo APK, o app Painel ASX como um todo
é distribuído nos termos da AGPLv3. O código-fonte completo é este repositório público
(https://github.com/somosvinciag-droid/painel-asx), o que cumpre a AGPL.

Este software não é certificado pelo Google. "Android Auto" é marca do Google LLC.

## Certificado de referência do Android Auto (`app/src/main/assets/aa/`)

`cert.pem` e `chave.pem` são o certificado e a chave de referência ("Google-Android-Reference", emitido por
"Google Automotive Link", válido de 2014 a 2044) que os receptores abertos usam. Foram copiados de
`app/src/main/res/raw/cert` e `privkey` do Headunit Revived; o mesmo par está publicado em vários projetos
abertos (aasdk/OpenAuto, headunit). Não é segredo do Leonardo e não dá acesso a nada além de o celular aceitar
a central como "receptor de Android Auto". Risco: o Google pode deixar de aceitá-lo um dia.

## Fonte Big Shoulders Display

`app/src/main/assets/fontes/`: SIL Open Font License (ver `OFL.txt`).

## sherpa-onnx

`app/src/main/java/com/k2fsa/sherpa/onnx/`: Apache License 2.0 (projeto k2-fsa/sherpa-onnx).

## scrcpy (servidor do canal pelo cabo)

`app/canal-servidor/src/br/com/leonardo/painelasx/canal/servidor/Ajustes.java`, `ContextoFalso.java` e
`MicAndroid.java` adaptam `Workarounds.java`, `FakeContext.java` e `AudioDirectCapture.java` do scrcpy
(https://github.com/Genymobile/scrcpy), Copyright (C) 2018 Genymobile e Copyright (C) 2018-2026 Romain Vimont,
Apache License 2.0. O cliente ADB (`app/src/main/java/br/com/leonardo/painelasx/canal/AdbUsb.kt`) foi escrito a
partir da documentação do protocolo do AOSP (`system/core/adb/protocol.txt`, Apache License 2.0).
