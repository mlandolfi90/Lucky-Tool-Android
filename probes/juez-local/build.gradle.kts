// Throwaway probe (FASE 0 of the signed-plan design): measures whether a small on-device model, run with
// LiteRT-LM inside our own process, can judge "is this action the current step of the approved plan?" while
// another app is in the foreground. Not part of the shipped app; versions mirror the main build.
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}
