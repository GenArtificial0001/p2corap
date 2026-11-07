# 08 · Perfiles, publicaciones e historias offline (diseño, **no implementado**)

> Pedido: foto de perfil, publicar fotos como en una red social y subir historias, todo guardado en SQLite local
> y visible sin internet. Este documento fija el diseño antes de escribir código, porque **cambia el principio
> «Zero-Storage»** de Radar y conviene decidirlo conscientemente.

## Qué cambia

| Hoy | Con perfiles/historias |
|---|---|
| Nada se guarda; el chat muere con el vínculo | Se persiste en SQLite: **tu** perfil y tus publicaciones; y una **caché con vencimiento** de lo de otros |
| Sin base de datos | `SQLite` (Room o `SQLiteOpenHelper`), excluido de backups |

Recomendación de privacidad (para decidir):

* **Lo propio persiste** (perfil, tus publicaciones): es tu contenido.
* **Lo ajeno es caché con vencimiento**: historias 24 h, publicaciones 7 días, máximo N MB; botón «Borrar todo».
* El **chat 1 a 1 sigue siendo efímero** (no se guarda).
* Cifrado en reposo con clave del Android Keystore (SQLCipher o cifrar blobs con AES-GCM) si el contenido es sensible.

## Modelo de datos

```
profile(id=1, alias, bio, avatar_jpeg BLOB ≤ 12 KB (128 px), updated_at)
post(id TEXT pk, author_id, author_alias, text, image_jpeg BLOB ≤ 64 KB, created_at, expires_at, is_mine)
story(id TEXT pk, author_id, image_jpeg BLOB ≤ 48 KB, caption, created_at, expires_at, is_mine)
seen(post_id)                       -- para no pedir dos veces lo mismo
```

`author_id` = clave pública Ed25519 efímera por instalación (sin cuentas). Cada publicación va **firmada**; el
receptor verifica la firma, así un nodo intermedio no puede falsificar autoría (sí puede descartar).

## Sincronización sin internet (gossip por BLE / Wi-Fi local)

Al vincularse dos teléfonos:

1. Intercambian un **catálogo** liviano: lista de `(id, created_at)` de lo vigente (≈20 B por ítem).
2. Cada uno pide con `WANT(ids)` lo que le falta; el otro responde con el contenido usando el **mismo transporte de
   medios** (cabecera + trozos cifrados, ya implementado en `07`).
3. Se respeta el vencimiento y un tope por autor para evitar spam.

Es *store-and-forward* de largo plazo: un tablón comunitario que se propaga por donde caminan las personas.
Útil en un apagón para avisos, refugios, personas buscadas, recursos.

## Piezas a construir

1. `data/Db.kt`, DAOs y migraciones; `ProfileRepository`, `FeedRepository`.
2. Tipos de paquete `CATALOG`, `WANT`, `POST`, `STORY`, `PROFILE` (reusan `MediaHeader/Chunk` para los blobs).
3. Firmas Ed25519 (JCA en Android 13+, o `java.security` con `KeyPairGenerator("Ed25519")`; en API < 33 usar una
   librería o ECDSA P-256 del Keystore).
4. UI: pestaña **Tablón** (feed + historias en carrusel con caducidad), editor de perfil, visor de historias.
5. Moderación local: silenciar autor, reportar (oculta), tope de contenido por autor, mismo velo «toca para ver».

## Riesgos

* Contenido ilegal o abusivo en un tablón sin moderación central ⇒ velo por defecto, bloqueo por autor, caducidad
  corta, y límite de lo que se retransmite (solo lo que el usuario vio y no ocultó).
* Almacenamiento: tope duro y limpieza por vencimiento.
* Privacidad: la foto de perfil se comparte con quien se vincula; que sea opcional y por sala.
