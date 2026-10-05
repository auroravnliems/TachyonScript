package dev.tachyonscript.security;

import java.util.List;

public final class SecurityPolicyEngine {
    private SecurityPolicyEngine() { }
    public static SecurityDecision decide(List<SecurityFinding> findings, SecurityOptions options) {
        boolean warn = false;
        for (SecurityFinding finding : findings) {
            if (finding.origin() != SecurityFinding.Origin.AI && finding.severity() == SecuritySeverity.CRITICAL
                    && finding.concreteEvidence()) return SecurityDecision.QUARANTINE;
            if (finding.origin() == SecurityFinding.Origin.AI && finding.severity() == SecuritySeverity.CRITICAL
                    && finding.confidence() >= options.ai().disableConfidence() && finding.location().known()
                    && finding.concreteEvidence() && !finding.nodeId().isBlank() && !finding.evidence().isBlank())
                return SecurityDecision.QUARANTINE;
            warn |= finding.origin() != SecurityFinding.Origin.AI || finding.confidence() >= options.ai().warnConfidence();
        }
        return warn ? SecurityDecision.WARN : SecurityDecision.ALLOW;
    }
}
