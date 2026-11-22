package hu.fokusz

/** A fekvőtámasz felső és alsó pontja. Az eredmény csak lezárt mozdulatnál változik. */
internal class PushupCycle {
    private enum class Phase { START, HIGH, DESCENT, LOW }
    private var phase = Phase.START
    private var descentAt = 0L
    private var lastAcceptedAt: Long? = null
    var moving = false
        private set

    fun reset() {
        phase = Phase.START
        moving = false
    }

    fun track(angle: Double, now: Long): PoseFeedback {
        val degrees = angle.toInt()
        if (phase == Phase.START) {
            if (angle >= 145) {
                phase = Phase.HIGH
                return PoseFeedback("Felső helyzet érzékelve. Engedd le magad.")
            }
            return PoseFeedback("A felső helyzetet még nem érzékeltem. Nyújtsd ki a karod legalább 145°-ig. ($degrees°)")
        }
        if (phase == Phase.HIGH && angle < 138) {
            phase = Phase.DESCENT
            moving = true
            descentAt = now
        }
        if (phase == Phase.DESCENT) {
            if (angle <= 120) {
                phase = Phase.LOW
                return PoseFeedback("Megvan a mélység. Nyomd fel magad.")
            }
            if (angle >= 145) {
                phase = Phase.HIGH
                moving = false
                val reason = "A karod nem érte el a 120°-os alsó helyzetet."
                return PoseFeedback(reason, rejection = reason)
            }
        }
        if (phase == Phase.LOW && angle >= 145) {
            phase = Phase.HIGH
            moving = false
            val previous = lastAcceptedAt
            if (now - descentAt < 160L || (previous != null && now - previous < 450L)) {
                val reason = "A mozdulat túl gyors volt. Lassíts a leengedésnél és a kinyomásnál."
                return PoseFeedback(reason, rejection = reason)
            }
            lastAcceptedAt = now
            return PoseFeedback("Fekvőtámasz +1", rep = true)
        }
        return if (phase == Phase.LOW) PoseFeedback("A felső helyzetet még nem érzékeltem. Nyomd fel magad legalább 145°-ig. ($degrees°)")
            else PoseFeedback("Hajlítsd a könyököd legfeljebb 120°-ig. ($degrees°)")
    }
}
