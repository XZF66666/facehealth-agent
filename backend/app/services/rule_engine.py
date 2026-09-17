from app.schemas.health import HealthMetrics, RuleAssessment, UserContext, WeeklySummary


class HealthRuleEngine:
    RED_FLAG_TERMS = {"胸痛", "明显胸闷", "持续心悸", "晕厥", "呼吸困难", "出冷汗"}

    def assess(
        self,
        metrics: HealthMetrics | None,
        context: UserContext | None = None,
        weekly: WeeklySummary | None = None,
    ) -> RuleAssessment:
        context = context or UserContext()
        weekly = weekly or WeeklySummary()
        flags: list[str] = []
        findings: list[str] = []
        risk_level = "normal"

        if metrics is None:
            return RuleAssessment(
                risk_level="insufficient_data",
                flags=["NO_TODAY_DATA"],
                findings=["今天尚无有效检测数据"],
            )

        quality = metrics.video_quality.strip().lower()
        if quality in {"差", "不合格", "poor", "invalid"}:
            flags.append("LOW_VIDEO_QUALITY")
            findings.append("本次视频质量不足，指标仅供参考")

        resting_context = not context.is_after_exercise
        if resting_context and metrics.heart_rate < 50:
            flags.append("LOW_HEART_RATE")
            findings.append("静息心率偏低")
        elif resting_context and metrics.heart_rate > 100:
            flags.append("HIGH_HEART_RATE")
            findings.append("静息心率偏高")

        if metrics.respiratory_rate < 10:
            flags.append("LOW_RESPIRATORY_RATE")
            findings.append("呼吸率偏低")
        elif metrics.respiratory_rate > 22:
            flags.append("HIGH_RESPIRATORY_RATE")
            findings.append("呼吸率偏高")

        if metrics.stress_score >= 70:
            flags.append("HIGH_STRESS")
            findings.append("压力指数偏高")
        if metrics.fatigue_score >= 70:
            flags.append("HIGH_FATIGUE")
            findings.append("疲劳指数偏高")
        if context.sleep_hours and context.sleep_hours < 6:
            flags.append("SHORT_SLEEP")
            findings.append("睡眠时长不足 6 小时")
        if context.is_late_sleep:
            flags.append("LATE_SLEEP")
            findings.append("存在熬夜情况")

        if weekly.avg_hrv > 0 and metrics.hrv < weekly.avg_hrv * 0.8:
            flags.append("HRV_BELOW_BASELINE")
            findings.append("HRV 较近 7 天平均水平下降超过 20%")
        elif metrics.hrv < 30:
            flags.append("LOW_HRV")
            findings.append("HRV 偏低，恢复状态可能不足")

        symptoms = {item.strip() for item in context.symptoms if item.strip()}
        red_flags = sorted(symptoms & self.RED_FLAG_TERMS)
        forced_warning = ""
        if red_flags:
            flags.append("RED_FLAG_SYMPTOM")
            findings.append("出现需要优先关注的不适症状：" + "、".join(red_flags))
            risk_level = "urgent"
            forced_warning = (
                "如胸痛、明显胸闷、呼吸困难、晕厥、出冷汗或持续心悸仍在持续，"
                "请停止运动并及时就医。"
            )
        elif any(flag in flags for flag in ("HIGH_HEART_RATE", "HIGH_RESPIRATORY_RATE", "HIGH_STRESS", "HIGH_FATIGUE")):
            risk_level = "attention"
        elif flags:
            risk_level = "observe"

        return RuleAssessment(
            risk_level=risk_level,
            flags=flags,
            findings=findings or ["当前未触发明显异常规则"],
            forced_warning=forced_warning,
            metadata={"resting_context": resting_context},
        )

