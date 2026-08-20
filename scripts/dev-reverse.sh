#!/usr/bin/env bash
#
# Abre los túneles USB que el build debug necesita para llegar al backend local.
#
# El build debug tiene la base hardcodeada en localhost (app/build.gradle.kts),
# y en el teléfono `localhost` es el teléfono: sin estos túneles el login muere
# en la capa de red, antes de salir del dispositivo, y el gateway ni se entera
# —no hay nada en sus logs porque nunca le llegó el request—.
#
# Los reverses viven en la sesión del servidor adb, no en el teléfono ni en el
# repo. Se pierden al desenchufar el USB, al reiniciar el servidor adb (Android
# Studio arranca el suyo) o al reiniciar el teléfono. No avisan al caerse, así
# que conviene correr esto antes de cada sesión de prueba: es idempotente.
#
# Uso: scripts/dev-reverse.sh
set -euo pipefail

# 8000 es el api-gateway; 8084 es auth-service directo, que el flujo de SSO usa
# sin pasar por el gateway (ver el comentario en ApiEnvironment.kt).
PUERTOS=(8000 8084)

# adb no está en el PATH. La ruta del SDK sale de local.properties, que es lo
# que Android Studio mantiene actualizado; el default cubre el caso de que ese
# archivo no exista (es local y no se versiona).
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$(sed -n 's/^sdk\.dir=//p' "$RAIZ/local.properties" 2>/dev/null || true)"
ADB="${SDK:-$HOME/Android/Sdk}/platform-tools/adb"

if [[ ! -x "$ADB" ]]; then
    echo "No encontré adb en $ADB" >&2
    exit 1
fi

# Un teléfono en estado `unauthorized` o `offline` acepta el `adb reverse` y
# después falla raro, así que se corta acá con un mensaje que dice qué pasa.
DISPOSITIVOS="$("$ADB" devices | tail -n +2 | grep -c '\sdevice$' || true)"
if [[ "$DISPOSITIVOS" -eq 0 ]]; then
    echo "No hay ningún teléfono conectado y autorizado:" >&2
    "$ADB" devices >&2
    exit 1
fi

for puerto in "${PUERTOS[@]}"; do
    "$ADB" reverse "tcp:$puerto" "tcp:$puerto" > /dev/null
    echo "túnel abierto: teléfono:$puerto -> máquina:$puerto"
done

# Verificar desde el teléfono y no desde la máquina: que el backend conteste acá
# no dice nada sobre si el túnel funciona, que es justo lo que falla en silencio.
# Se sondea la raíz y alcanza con que conteste *cualquier* código HTTP: un 404
# del gateway prueba que el request cruzó el túnel igual de bien que un 200, y
# no ata el chequeo a que exista tal o cual endpoint. Lo que se distingue es
# "contestó algo" contra el 000 de curl, que es no haber conectado.
echo
for puerto in "${PUERTOS[@]}"; do
    codigo="$("$ADB" shell "curl -s -m 8 -o /dev/null -w '%{http_code}' http://localhost:$puerto/" || true)"
    if [[ "$codigo" == "000" || -z "$codigo" ]]; then
        echo "FALLA :$puerto — el teléfono no llega a la máquina (¿el backend está levantado?)"
    else
        echo "OK    :$puerto — el teléfono llega a la máquina (HTTP $codigo)"
    fi
done
