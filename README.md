# Dreamers Triggers

Plugin de Paper para Minecraft 26.3. Avisa a todo el servidor cuando un jugador muere o cuando un tótem de la resurrección lo salva.

La carpeta `.datapack` guarda la versión anterior, hecha como datapack. No la cargues junto con el plugin: los dos anunciarían la misma muerte y el mismo tótem.

## Muerte

Cuando un jugador muere, todos ven un título:

- **Título:** `Fallen Player`
- **Subtítulo:** el mismo mensaje de muerte que el juego escribe en el chat. Por ejemplo, `Jugador fue detonado(a) por Creeper`.

El mensaje de chat de la muerte no se quita. El título se suma a ese aviso.

Al mismo tiempo suena `minecraft:terranova.death` para todos los jugadores conectados. El sonido va en la categoría de ambiente y se reproduce en cada jugador, así que no se oye desde el lugar donde murió alguien.

## Tótem

Si un tótem evita la muerte, el chat muestra un mensaje amarillo con el nombre del jugador:

- Tótem sin renombrar: `Jugador toteó pótem! (Daño de caída)`
- Tótem con nombre de yunque: `Jugador consumió Tótem pro! (Se ahogó)`

El texto entre paréntesis es la causa del golpe que activó el tótem. Si hubo un atacante, el mensaje lo incluye, como en `Detonado(a) por Creeper` o `Flecha de Esqueleto`. El nombre que puso el jugador en el yunque conserva su formato.

El sonido `minecraft:terranova.totem` solo lo oye quien consumió el tótem.

## Resource pack

Los dos sonidos no están dentro del plugin. Salen del resource pack Terranova, con estos eventos:

- `minecraft:terranova.death`
- `minecraft:terranova.totem`

Sin ese pack, el título y el chat funcionan igual y el sonido no se oye.

## Compilar e instalar

Hace falta JDK 25. Gradle descarga esa versión si el sistema usa otra.

```powershell
.\gradlew.bat build
```

El jar queda en `build/libs/Dreamers-Triggers-1.1.jar`. Cópialo a la carpeta `plugins` de un servidor Paper 26.3.
