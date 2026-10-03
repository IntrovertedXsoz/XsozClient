package dev.xsoz.core.setting;

/**
 * Renders a setting's value into a report line, honouring the {@code sensitive} flag.
 *
 * <p>A report is shown on a settings screen and appended to a recovery log. A
 * {@code sensitive} value must not appear in either (contracts.md C4.6, 0.5), so the
 * report names the key and the reason and substitutes {@link Setting#REDACTED} for the
 * value itself.</p>
 *
 * <p><strong>This is presentation, not behaviour.</strong> It runs after validation has
 * already decided what the value is, and nothing downstream reads the report to make a
 * decision.</p>
 */
final class SettingReportText {

    private SettingReportText() {
    }

    static String of(Setting setting, Object value) {
        return setting.key() + "=" + setting.redact(value);
    }
}
