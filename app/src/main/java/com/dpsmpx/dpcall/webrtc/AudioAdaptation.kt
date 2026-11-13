package com.dpsmpx.dpcall.webrtc

/**
 * Подбирает битрейт Opus по состоянию канала: при потерях и большой задержке
 * снижает его (FEC и меньший пакет переживают потери лучше), при чистом канале
 * постепенно возвращает. Вниз — быстро, вверх — осторожно, чтобы не качало.
 */
class BitrateController {
    var bitrate: Int = STEPS[0]
        private set
    private var index = 0
    private var bad = 0
    private var good = 0

    /**
     * @param lossPercent потери наших пакетов у собеседника за интервал опроса (2 с).
     * @return новый битрейт, если его нужно сменить, иначе null.
     */
    fun onSample(lossPercent: Double?, rttMs: Double?): Int? {
        val loss = lossPercent ?: 0.0
        val rtt = rttMs ?: 0.0
        val target = when {
            loss >= SEVERE_LOSS -> {
                // Сильные потери — сразу на ступень ниже, без ожидания второго замера.
                bad = 0
                good = 0
                index + 1
            }
            loss >= BAD_LOSS || rtt >= BAD_RTT_MS -> {
                good = 0
                if (++bad >= BAD_SAMPLES) {
                    bad = 0
                    index + 1
                } else index
            }
            loss < GOOD_LOSS && rtt < GOOD_RTT_MS -> {
                bad = 0
                if (++good >= GOOD_SAMPLES) {
                    good = 0
                    index - 1
                } else index
            }
            else -> {
                bad = 0
                good = 0
                index
            }
        }.coerceIn(0, STEPS.lastIndex)
        if (target == index) return null
        index = target
        bitrate = STEPS[index]
        return bitrate
    }

    companion object {
        /** Ступени битрейта, бит/с: от полной полосы до узкой, но разборчивой речи. */
        val STEPS = listOf(48_000, 32_000, 24_000, 16_000)
        const val SEVERE_LOSS = 15.0
        const val BAD_LOSS = 5.0
        const val GOOD_LOSS = 1.5
        const val BAD_RTT_MS = 600.0
        const val GOOD_RTT_MS = 300.0
        const val BAD_SAMPLES = 2
        /** 5 замеров по 2 с: битрейт растёт не чаще раза в 10 секунд. */
        const val GOOD_SAMPLES = 5
    }
}

/** Насколько хорошо слышно собеседника — по доле звука, восполненного вместо потерянного. */
enum class HearQuality { UNKNOWN, GOOD, FAIR, POOR }

object HearingEstimate {
    /**
     * @param concealedPercent доля «додуманных» сэмплов за интервал: потерянные или
     *   опоздавшие пакеты, которые декодер заменил подстановкой.
     * @param packetsArrived пришёл ли за интервал хоть один пакет звука.
     */
    fun of(concealedPercent: Double?, packetsArrived: Boolean): HearQuality = when {
        !packetsArrived -> HearQuality.POOR
        concealedPercent == null -> HearQuality.UNKNOWN
        concealedPercent < 3.0 -> HearQuality.GOOD
        concealedPercent < 10.0 -> HearQuality.FAIR
        else -> HearQuality.POOR
    }
}
