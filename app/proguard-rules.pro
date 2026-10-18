-dontwarn org.conscrypt.**

# Cartelle condivise (SMB): smbj e il suo bus di eventi (mbassador) trovano i metodi per nome e per
# annotazione; DCE/RPC legge e scrive le strutture dei messaggi. Si tengono interi.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-keep class com.rapid7.helper.smbj.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Kerberos/GSS-API e JNDI non esistono su Android: smbj e BouncyCastle li usano solo se presenti.
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.security.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn java.lang.management.**
# dcerpc usa java.rmi solo per segnalare messaggi malformati; MBassador usa javax.el solo per i
# filtri con espressioni, che smbj non usa. Su Android non ci sono: SmbShares gestisce l'errore.
-dontwarn java.rmi.**
-dontwarn javax.el.**
