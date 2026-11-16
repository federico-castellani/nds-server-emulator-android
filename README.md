# NDS Station per Android

Client Android di NDS Station: accedi al tuo server, scegli un gioco dalla libreria e giochi sul telefono con il core di [melonDS](https://melonds.kuribo64.net) e i tasti virtuali su schermo. Ha le stesse funzioni del [client Windows](https://github.com/federico-castellani/nds-server-emulator-windows) e usa lo stesso server ([nds-server-emulator-fastapi](https://github.com/federico-castellani/nds-server-emulator-fastapi)).

## Architettura

```
app/src/main/java/…/ndsstation/
  data/         ApiClient (REST, OkHttp), impostazioni, archivio locale, identità console
  emulation/    sessione di gioco, sincronizzazione salvataggi, input, banner ROM
  ui/           Compose + Material 3 (colori dinamici): login, libreria, emulatore,
                tasti virtuali, amministrazione, impostazioni
app/src/main/cpp/   libndscore.so: JNI attorno a melonDS (thread di emulazione,
                    audio AAudio, rete Wi-Fi con libslirp)
external/melonDS    submodule, fissato allo stesso commit del client Windows
```

## Funzionalità

- Sessione ricordata (token cifrato con Android Keystore), cambio password obbligatorio al primo accesso.
- Libreria con ricerca; icona e titolo dal banner delle ROM già scaricate.
- Emulatore con tasti virtuali (croce, A/B/X/Y, L/R, Start/Select), touch sullo schermo inferiore, controller fisici, pausa, avanti veloce, stati salvati.
- Salvataggi sincronizzati con il server: copia locale immediata, invio dopo qualche secondo e quando l'app va in background. Se su un altro dispositivo c'è un progresso più recente, all'uscita scegli se sovrascriverlo.
- Stessa identità di console (ID Nintendo WFC) su tutti i dispositivi dell'account: la lista amici resta valida passando dal PC al telefono.
- Nintendo WFC: lotte e scambi online, anche tra giocatori sulla stessa rete di casa (relay LAN compatibile con il client Windows).
- Pannello di amministrazione: utenti, password, caricamento giochi.

## Compilare

Servono Android SDK con NDK 27 e un JDK 17–21 (`winget install EclipseAdoptium.Temurin.21.JDK`).

```powershell
git submodule update --init --recursive
.\build.ps1            # release; .\build.ps1 -Debug per la variante di debug
```

L'APK finisce in `dist\`. Le librerie native sono allineate a 16 KB, come richiesto da Android 15+.

## Licenza

GPLv3 (vedi `LICENSE`), come melonDS che include.
