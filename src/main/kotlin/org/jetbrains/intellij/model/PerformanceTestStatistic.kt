package org.jetbrains.intellij.model

class PerformanceTestStatistic private constructor(
    val totalTime: Long?,
    val responsive: Long?,
    val averageResponsive: Long?,
) {

    data class Builder(
        var totalTime: Long? = null,
        var responsive: Long? = null,
        var averageResponsive: Long? = null,
    ) {
        fun totalTime(value: Long?) = apply { totalTime = value }

        fun responsive(value: Long?) = apply { responsive = value }

        fun averageResponsive(value: Long?) = apply { averageResponsive = value }

        fun build() = PerformanceTestStatistic(totalTime, responsive, averageResponsive)
    }
}
