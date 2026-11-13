package com.dpsmpx.dpcall.signaling.relay

import kotlin.math.min

/**
 * Надёжность одного релея по его поведению: обрывы соединения, отказы принять событие
 * и неподтверждённые отправки. Релей, который раз за разом подводит, временно
 * отстраняется, а его место занимает запасной. Без сети и Android — проверяется тестами.
 */
class RelayHealth {
    /** Неудачные подключения и быстрые обрывы подряд. */
    var failures = 0
        private set
    /** Отказы принять событие (OK false) подряд. */
    var rejects = 0
        private set
    /** Отправки без подтверждения подряд. */
    var timeouts = 0
        private set
    /** Сколько событий релей принял за всё время. */
    var accepted = 0
        private set
    var benchedUntil = 0L
        private set
    private var benchCount = 0

    fun isBenched(now: Long) = now < benchedUntil

    /** Пора отстранить: релей подводит несколько раз подряд. */
    val failing: Boolean
        get() = failures >= MAX_FAILURES || rejects >= MAX_REJECTS || timeouts >= MAX_TIMEOUTS

    fun onConnectFailure() {
        failures++
    }

    /** Соединение проработало достаточно долго — обрывы до этого были случайностью. */
    fun onStable() {
        failures = 0
    }

    fun onAck(ok: Boolean) {
        timeouts = 0
        if (ok) {
            accepted++
            rejects = 0
            failures = 0
            benchCount = 0
        } else {
            rejects++
        }
    }

    fun onAckTimeout() {
        timeouts++
    }

    /** Отстраняет релей; каждое повторное отстранение вдвое дольше предыдущего. */
    fun bench(now: Long) {
        val duration = min(BENCH_BASE_MS shl min(benchCount, 6), BENCH_MAX_MS)
        benchCount++
        benchedUntil = now + duration
        failures = 0
        rejects = 0
        timeouts = 0
    }

    companion object {
        const val MAX_FAILURES = 3
        const val MAX_REJECTS = 2
        const val MAX_TIMEOUTS = 3
        const val BENCH_BASE_MS = 5 * 60_000L
        const val BENCH_MAX_MS = 60 * 60_000L

        /**
         * Какие релеи держать подключёнными: все основные, кроме отстранённых, а если
         * их меньше [target] — столько запасных, сколько не хватает.
         */
        fun choose(primary: List<String>, spare: List<String>, target: Int, benched: (String) -> Boolean): List<String> {
            val active = primary.filterNot(benched)
            val missing = target - active.size
            if (missing <= 0) return active
            return active + spare.filterNot { it in primary || benched(it) }.take(missing)
        }
    }
}
