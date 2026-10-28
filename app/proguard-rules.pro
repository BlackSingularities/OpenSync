# Compose i AndroidX dostarczają własne reguły konsumenckie.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# SMB (smbj + mbassador), SFTP (sshj) i BouncyCastle korzystają z refleksji i dostawców JCA.
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class com.jcraft.jzlib.** { *; }

-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**
-dontwarn net.engio.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
