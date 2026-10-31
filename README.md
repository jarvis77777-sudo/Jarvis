# Jarvis for Android

A voice assistant with a glowing blue orb.

- Say **"Hey Jarvis"**, or tap the orb on your home screen.
- Replies in a British, butler-style voice, powered by Claude.
- Opens apps and websites, searches Google, and calls people from your contacts.

## 1. Build it (about 15 minutes)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. `File > Open`, then choose this `Jarvis` folder. Wait for "Gradle sync" to finish.
   If it offers to upgrade the Android Gradle Plugin, you can say yes or ignore it.
3. On your phone: `Settings > About phone`, tap **Build number** 7 times. Then open
   `Settings > Developer options` and turn on **USB debugging**.
4. Plug the phone into your computer and accept the "Allow USB debugging" prompt.
5. Press the green **Run** button in Android Studio and pick your phone.

## 2. First-time setup in the app

1. Tap **Grant permissions** and allow Microphone, Contacts, Phone and Notifications.
2. Tap **Allow opening apps from the background** and switch it on.
   Without it Android blocks Jarvis from opening apps or calling while another app is on screen.
3. Tap **Disable battery optimisation** and choose Allow, so Android doesn't kill him.
4. Get an API key at https://console.anthropic.com (Settings > API keys), paste it in,
   and tap **Save settings**. This is what lets him hold a real conversation.
   Without a key he still handles basic commands like "open YouTube" or "call Mum".
5. Tap **Start Jarvis**. A "Jarvis is listening" notification appears. That's normal, and it is
   what keeps him alive in the background.

## 3. Put the orb on your home screen

Long-press an empty spot on the home screen, choose **Widgets**, find **Jarvis**, and drag the
blue orb out. Tapping it makes Jarvis say "Yes, sir?" and listen for your command.

## Things to say

- "Hey Jarvis, open Spotify."
- "Hey Jarvis, call Mum." (it matches names in your contacts)
- "Hey Jarvis, go to bbc.com."
- "Hey Jarvis, search for the best pizza in Zurich."
- "Hey Jarvis, what time is it?" or any normal question or chat.

## Making the voice sound more like Jarvis

The code asks for a deep-ish British voice. For the best result, open
`Settings > System > Language > Text-to-speech output`, then install or pick a
**English (UK) male** voice in Google Text-to-speech. The app picks one automatically
when it can.

## Honest limitations

- **Wake word:** "Hey Jarvis" works by running Google's speech recogniser in a loop. It
  has a delay of about 1 to 2 seconds. It uses some battery, and on some phones it can make a
  small beep each time it restarts. If that annoys you, the proper fix is a dedicated
  wake-word engine such as Picovoice Porcupine (free tier), which I can add.
- **Restarts:** after rebooting the phone, open the Jarvis app once. Android 14+ doesn't
  let apps start a microphone service by themselves at boot.
- **Locked screen:** he can hear you, but Android won't open apps or place calls until the
  phone is unlocked.
- **Cost:** each reply costs a fraction of a cent. The model is set in `Brain.kt`
  (`MODEL`). The key is stored only on your phone.
