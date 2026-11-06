# Reglas de R8/ProGuard para Radar.
# El proyecto no usa reflexión ni serialización, así que no hace falta
# conservar nada en particular. Las reglas quedan acá como punto de extensión.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
