package com.celtech.solutions.cgsKitchen.models.mail;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MailMessage {
    /** Single primary recipient. CC/BCC can be added later if needed. */
    String to;
    /** Optional override of the configured "from" — most callers leave this null. */
    String fromOverride;
    /** Subject line, single line, ≤ 998 chars per RFC 5322. */
    String subject;
    /** HTML body; required. */
    String html;
    /** Plain-text fallback. Recommended for spam-score reasons. May be null. */
    String text;
}
