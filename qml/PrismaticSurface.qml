import QtQuick

Item {
    id: root

    property bool darkMode: false
    property real radius: 24
    property real backgroundOpacity: 1
    property bool elevated: false
    property bool iridescent: true

    onDarkModeChanged: finish.requestPaint()
    onRadiusChanged: finish.requestPaint()
    onElevatedChanged: finish.requestPaint()
    onIridescentChanged: finish.requestPaint()

    Canvas {
        id: finish
        anchors.fill: parent
        opacity: Math.max(0, Math.min(1, root.backgroundOpacity))
        renderTarget: Canvas.Image
        antialiasing: true

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onAvailableChanged: if (available) requestPaint()

        function roundedPath(context: var, inset: real): void {
            var left = inset
            var top = inset
            var right = width - inset
            var bottom = height - inset
            var corner = Math.max(0, Math.min(root.radius - inset,
                                            (right - left) / 2,
                                            (bottom - top) / 2))
            context.beginPath()
            context.moveTo(left + corner, top)
            context.lineTo(right - corner, top)
            context.quadraticCurveTo(right, top, right, top + corner)
            context.lineTo(right, bottom - corner)
            context.quadraticCurveTo(right, bottom, right - corner, bottom)
            context.lineTo(left + corner, bottom)
            context.quadraticCurveTo(left, bottom, left, bottom - corner)
            context.lineTo(left, top + corner)
            context.quadraticCurveTo(left, top, left + corner, top)
            context.closePath()
        }

        function cornerWash(context: var, x: real, y: real, reach: real,
                            tint: string, strength: real): void {
            var wash = context.createRadialGradient(x, y, 0, x, y, reach)
            wash.addColorStop(0, "rgba(" + tint + "," + strength + ")")
            wash.addColorStop(0.34, "rgba(" + tint + "," + strength * 0.55 + ")")
            wash.addColorStop(0.72, "rgba(" + tint + "," + strength * 0.12 + ")")
            wash.addColorStop(1, "rgba(" + tint + ",0)")
            context.fillStyle = wash
            context.fillRect(x - reach, y - reach, reach * 2, reach * 2)
        }

        function cornerReflection(context: var, x: real, y: real, reach: real,
                                  tint: string, strength: real): void {
            var reflection = context.createRadialGradient(x, y, 0, x, y, reach)
            reflection.addColorStop(0, "rgba(" + tint + "," + strength + ")")
            reflection.addColorStop(0.48, "rgba(" + tint + "," + strength * 0.55 + ")")
            reflection.addColorStop(1, "rgba(" + tint + ",0)")
            roundedPath(context, 2.15)
            context.lineWidth = 1.7
            context.strokeStyle = reflection
            context.stroke()
        }

        onPaint: {
            var context = getContext("2d")
            context.reset()
            if (width <= 0 || height <= 0)
                return

            // All decoration shares this rounded clip; the reading plane stays solid.
            context.save()
            roundedPath(context, 0)
            context.clip()
            context.fillStyle = root.darkMode
                    ? (root.elevated ? "#252C43" : "#191C2C")
                    : (root.elevated ? "#F9F9FC" : "#F5F5FA")
            context.fillRect(0, 0, width, height)

            // A short card keeps its colour at the two ends; the centre remains opaque.
            var reach = Math.max(1, root.elevated
                                ? Math.min(width * 0.34, height * 0.75, 132)
                                : Math.min(width * 0.46, height * 0.42, 184))
            if (root.darkMode) {
                reach = Math.max(1, root.elevated
                                ? Math.min(width * 0.36, height * 0.78, 142)
                                : Math.min(width * 0.48, height * 0.44, 202))
            }
            var washStrength = root.darkMode ? 0.27 : 0.31
            if (root.iridescent) {
                if (root.darkMode) {
                    cornerWash(context, 0, 0, reach, "64,221,243", washStrength)
                    cornerWash(context, width, 0, reach * 0.82, "165,146,242", washStrength * 0.40)
                    cornerWash(context, 0, height, reach * 0.82, "107,169,239", washStrength * 0.40)
                    cornerWash(context, width, height, reach, "237,130,220", 0.24)
                } else {
                    cornerWash(context, 0, 0, reach, "76,218,239", washStrength)
                    cornerWash(context, width, 0, reach * 0.82, "173,157,239", washStrength * 0.68)
                    cornerWash(context, 0, height, reach * 0.82, "123,194,239", washStrength * 0.68)
                    cornerWash(context, width, height, reach, "238,143,225", washStrength)
                }
            }

            // A narrow spectrum sits between the outer and inner glass hairlines.
            roundedPath(context, 0.5)
            context.lineWidth = 1
            context.strokeStyle = root.darkMode ? "rgba(141,149,193,0.12)"
                                               : "rgba(162,170,204,0.26)"
            context.stroke()

            if (root.iridescent) {
                var edgeStrength = root.darkMode ? 0.80 : 0.60
                var spectrum = context.createLinearGradient(0, 0, width, height)
                spectrum.addColorStop(0, "rgba(" + (root.darkMode ? "64,221,243," : "76,218,240,") + edgeStrength + ")")
                spectrum.addColorStop(0.30, "rgba(130,193,241," + edgeStrength * (root.darkMode ? 0.50 : 0.42) + ")")
                spectrum.addColorStop(0.58, "rgba(191,176,242," + edgeStrength * (root.darkMode ? 0.4375 : 0.30) + ")")
                spectrum.addColorStop(1, "rgba(" + (root.darkMode ? "237,130,220," : "240,144,227,") + edgeStrength + ")")
                roundedPath(context, 2.15)
                context.lineWidth = 3.0
                context.strokeStyle = spectrum
                context.stroke()

                var reflectionStrength = root.darkMode ? 0.70 : 0.88
                cornerReflection(context, 0, 0, reach, "106,235,244", reflectionStrength)
                cornerReflection(context, width, height, reach, "247,165,235", reflectionStrength)
                cornerReflection(context, width, 0, reach * 0.82, "182,181,247", reflectionStrength * (root.darkMode ? 0.40 : 0.62))
                cornerReflection(context, 0, height, reach * 0.82, "122,218,244", reflectionStrength * (root.darkMode ? 0.40 : 0.62))
            }

            roundedPath(context, 1.0)
            context.lineWidth = 0.9
            context.strokeStyle = root.darkMode ? "rgba(242,244,255,0.10)"
                                               : "rgba(255,255,255,0.96)"
            context.stroke()

            roundedPath(context, 3.9)
            context.lineWidth = 0.7
            context.strokeStyle = root.darkMode ? "rgba(232,237,255,0.06)"
                                               : "rgba(255,255,255,0.80)"
            context.stroke()
            context.restore()
        }
    }
}
