# Dreamers Triggers

Plugin de Paper para Minecraft 26.3. Avisa a todo el servidor cuando un jugador muere o cuando un tótem de la resurrección lo salva.

`.datapack_old` es un datapack anterior: no lo cargues, porque anunciaría otra vez la muerte y el tótem. Los objetos custom no salen en el creativo. Un operador los entrega con `/dreamers give`.

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

El tótem normal se consigue como siempre. Los tótems custom se craftean, y el de los no-muertos lo sueltan zombis y esqueletos. No aparecen en el creativo.

## Modo UHC por muerte

`/dreamers DeathUCH on` lo deja pendiente. `/dreamers DeathUCH off` lo apaga y el juego sigue como siempre. `/dreamers DeathUCH time <segundos>` define cuánto dura; si no se cambia, son 60 segundos.

Con el modo activado, la muerte de un jugador apaga la regeneración natural durante ese tiempo. Si muere otro mientras sigue corriendo, se suma otra vez la misma duración al tiempo que queda. Todos ven una boss bar roja, `Modo UHC por muerte de un jugador`, que se vacía según el tiempo restante. Al llegar a cero, la regeneración vuelve a como estaba.

`/dreamers reload` vuelve a leer `config.yml`.

`/dreamers give <jugador> <objeto> <cantidad>` le entrega un objeto custom a un jugador conectado. La cantidad va de 1 a 99. Solo lo puede usar un operador o la consola. Los objetos son `nutritotem`, `totem_ferreo`, `totem_yunque`, `totem_dreamer`, `totem_no_muertos`, `enderbag`, `saco_mediano`, `saco_grande`, `saco_shulker`, `reforzada_casco`, `reforzada_pechera`, `reforzada_pantalones`, `reforzada_botas`, `reforzada_hacha`, `reforzada_azada`, `reforzada_pico`, `reforzada_pala`, `reforzada_espada` y `reforzada_lanza`. El tabulador los lista.

## Comandos

Quien no es op y no tiene el permiso `dreamers.commands.bypass` solo puede usar los comandos de `commands.allowed`. El tabulador le ofrece únicamente esos. Si prueba otro, ve el texto de `commands.blocked`. Un op puede usar cualquiera. La consola no se bloquea.

`minecraft:tell` cuenta como `tell`. La lista que trae el plugin es la que ya usaba el servidor: `tell`, `msg`, `me` y los de reclamaciones. Al actualizar, si `config.yml` todavía no tiene esa sección, el plugin la añade sin tocar el modo UHC.

## Inventario

`/dreamers inventory <jugador>` y `/dreamers enderchest <jugador>` los abre un operador. Un jugador que no es op no puede usarlos. Sirven con el jugador conectado o desconectado, y lo que se mueve entra o sale de su inventario al momento.

En el inventario, la primera fila es la barra. Las tres siguientes son el resto. Debajo están las botas, los pantalones, la pechera, el casco, la mano secundaria, el cursor, el cuerpo y la montura. La última fila muestra la cuadrícula de crafteo de 2×2 y el resultado, o los huecos de la mesa que ese jugador tenga abierta. Tomar el resultado del crafteo 2×2 entrega el objeto y gasta los materiales. El cursor y el crafteo no quedan guardados cuando el jugador está desconectado.

## Experiencia embotellada

Con una botella de cristal en la mano principal, el clic derecho en una mesa de encantamientos guarda experiencia: quita 7 puntos, gasta la botella de cristal y entrega una botella de experiencia. Esos 7 puntos son la media que suelta una botella al lanzarla (entre 3 y 11). Si no cabe en el inventario, cae al suelo. La mesa no se abre mientras se sostiene la botella. Si no alcanza la experiencia, el chat avisa en rojo. Cualquier jugador puede hacerlo.

## Hojas

Al talar un tronco, o cuando una hoja empieza a pudrirse, las hojas de alrededor que ya no tienen tronco se rompen en el acto, también en diagonal. Sueltan lo mismo que al pudrirse: brotes, manzanas, palos y el resto del botín de esa hoja. Las hojas colocadas por un jugador no se caen solas.

## Xaero's Minimap

El mod recibe si puede usar las cuevas, las cuevas del Nether y el radar de entidades. Quien no tiene una regla propia usa los valores de `xaero` en `config.yml`, que de inicio están permitidos. `/dreamers xm <jugador|@a> <cuevas> <cuevasNether> <radar> <trackPlayers>` guarda la regla con `true` o `false`. `@a` incluye a quien ya entró, esté conectado o no. Si está conectado, se le aplica al momento. `/dreamers xm <jugador|@a> reset` borra esa regla y vuelven los ajustes por defecto. Sigue valiendo después de reiniciar. `/dreamers reload` vuelve a leer la configuración y la reenvía. El `level-id` que ya usa el servidor se conserva.

`trackPlayers` muestra en el mapa y el minimapa a los demás jugadores, estén donde estén. Si está en `false`, solo aparecen cuando entran en el alcance en el que el cliente ya los vería. No salen quienes están en espectador, invisibles o vanished. `radar` apaga el radar de mobs y del resto de entidades, y no esconde a los jugadores.

## Botín único

Cofres, cofres trampa, barriles y vagonetas con cofre que todavía tienen tabla de botín se abren con un inventario por jugador. Lo que cada quien deja dentro se guarda en ese contenedor. La mitad de un cofre doble sin tabla sigue siendo la de siempre, compartida. El botín no se suelta al romper el contenedor.

Con `unique-loot.destroying.restricted` solo un jugador en creativo puede romperlos, y `confirmation` le pide confirmación. Mientras la restricción está activa, las explosiones, los pistones, las tolvas y los líquidos tampoco los destruyen. `auto-replenish` vuelve a generar el botín pasado un tiempo al azar entre `minutes-min` y `minutes-max`. `max-refills` cuenta cuántas veces se rellena; `-1` no tiene límite. `require-emptied` exige vaciarlo antes. `/dreamers reload` vuelve a leer estas opciones.

## AppleSkin

Quien entra con el mod AppleSkin recibe su saturación, su agotamiento y si el mundo en el que está regenera la vida solo. Así el HUD coincide con el servidor, también cuando el modo UHC por muerte apaga esa regeneración. Quien no tiene el mod no recibe esos datos.

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

El jar queda en `build/libs/Dreamers-Triggers-1.6.7.jar`. Cópialo a la carpeta `plugins` de un servidor Paper 26.3. Quita `CommandBlocker` de esa carpeta: este plugin ya bloquea los comandos.
