# Dreamers Triggers

Plugin de Paper para Minecraft 26.3. Avisa a todo el servidor cuando un jugador muere o cuando un tótem de la resurrección lo salva.

`.datapack` es el datapack que mete los tótems en el creativo. Cárgalo junto con el plugin. `.datapack_old` es la versión anterior: no la cargues, porque anunciaría otra vez la muerte y el tótem.

## Muerte

Cuando un jugador muere, todos ven un título:

- **Título:** `Fallen Player`
- **Subtítulo:** el mismo mensaje de muerte que el juego escribe en el chat, en blanco. Por ejemplo, `Jugador fue detonado(a) por Creeper`. En el chat, ese mensaje va en rojo.

El mensaje de chat de la muerte no se quita y se muestra en rojo. El título se suma a ese aviso.

Al mismo tiempo suena `dreamers:dreamers.effect_fortnite` para todos los jugadores conectados. El sonido va en la categoría de ambiente y se reproduce en cada jugador, así que no se oye desde el lugar donde murió alguien.

Quien murió pasa de inmediato a espectador en ese lugar y se queda ahí 3 segundos. En su chat aparece, en amarillo, `Moriste en la ubicación: X, Y, Z`. Después reaparece en su punto de respawn y solo él oye `dreamers:dreamers.effect_rbd`. Volver del End no lo dispara.

## Tótem

Si un tótem evita la muerte, el chat muestra un mensaje amarillo con el nombre del jugador:

- Tótem sin renombrar: `Jugador toteó pótem! (Daño de caída)`
- Tótem con nombre de yunque: `Jugador consumió Tótem pro! (Se ahogó)`

El texto entre paréntesis es la causa del golpe que activó el tótem. Si hubo un atacante, el mensaje lo incluye, como en `Detonado(a) por Creeper` o `Flecha de Esqueleto`. Si el jugador renombró el tótem, se usa ese nombre. El color del nombre es el de la variante: amarillo en el normal y en el Nutritótem, cian en el Férreo y en el de los no-muertos, y magenta claro en el Dreamer.

El sonido `dreamers:dreamers.effect_bell` solo lo oye quien consumió un tótem normal, aunque esté renombrado en el yunque. Las variantes usan otro sonido:

- **Nutritótem:** el sonido vanilla del tótem. Durante 2 segundos da resistencia que anula el daño y, además, los efectos normales del tótem. En supervivencia solo se craftea, con un tótem normal en el centro y, alrededor, diamante en las esquinas y esmeralda en el resto.
- **Tótem Férreo:** el sonido vanilla del tótem y los mismos efectos que el Nutritótem. En la mano secundaria da 2 corazones extra. En supervivencia solo se craftea, con un Nutritótem en el centro y bloques de hierro alrededor.
- **Tótem de los no-muertos:** el sonido vanilla del tótem, más grave (tono 0.75). En un mob no-muerto se porta como un tótem y, durante 1 segundo, aplica Wither I por 3 segundos en un área de 3×3 a su alrededor. Si lo usa un jugador, queda a 1 de vida, con Wither I (45 s), Debilidad II (40 s), Fatiga minera I (15 s) y la saturación al máximo. Las variantes de zombi y de esqueleto tienen un 15% de aparecer con uno en la otra mano y un 5% de soltar uno al morir. En supervivencia ese drop es la única forma de conseguirlo.
- **Tótem Dreamer:** `dreamers:dreamers.totem`. Sigue popeando desde el inventario. En supervivencia solo se craftea, con un tótem normal en el centro: diamante, fragmento de amatista y diamante arriba y abajo; tótem, ojo de ender y tótem en medio.

El tótem normal se consigue como siempre. Los cuatro tótems custom salen en el creativo de Java junto a los libros encantados, con la textura del tótem. Al tomarlos, el datapack `.datapack` los cambia al momento por un tótem de la resurrección. Hay que cargar ese datapack junto con el plugin. En Bedrock, Geyser los muestra en la categoría de equipo.

## Modo UHC por muerte

`/dreamers DeathUCH on` lo deja pendiente. `/dreamers DeathUCH off` lo apaga y el juego sigue como siempre. `/dreamers DeathUCH time <segundos>` define cuánto dura; si no se cambia, son 60 segundos.

Con el modo activado, la muerte de un jugador apaga la regeneración natural durante ese tiempo. Si muere otro mientras sigue corriendo, se suma otra vez la misma duración al tiempo que queda. Todos ven una boss bar roja, `Modo UHC por muerte de un jugador`, que se vacía según el tiempo restante. Al llegar a cero, la regeneración vuelve a como estaba.

## Discord

Si DiscordSRV está instalado, las muertes salen en español de México dentro del mismo aviso que ya usa: la cabeza del jugador, su nombre y el mensaje en negrita. Los logros anunciados usan ese mismo aviso dorado. El nombre va en negrita y la descripción, en español de México, en la línea de abajo y en cursiva. Un progreso dice «ha conseguido el progreso», un desafío «ha completado el desafío» y un objetivo «ha alcanzado el objetivo».

## Resource pack

Los sonidos no están dentro del plugin. Salen del resource pack, en el namespace `dreamers`:

- `dreamers:dreamers.effect_fortnite`
- `dreamers:dreamers.effect_bell`
- `dreamers:dreamers.effect_rbd`
- `dreamers:dreamers.totem`

Sin ese pack, el título y el chat funcionan igual y el sonido no se oye.

## Compilar e instalar

Hace falta JDK 25. Gradle descarga esa versión si el sistema usa otra.

```powershell
.\gradlew.bat build
```

El jar queda en `build/libs/Dreamers-Triggers-1.4.jar`. Cópialo a la carpeta `plugins` de un servidor Paper 26.3.
