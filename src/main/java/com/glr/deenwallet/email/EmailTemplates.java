package com.glr.deenwallet.email;

final class EmailTemplates {

    private static final String BRAND_COLOR = "#0F766E";
    private static final String TEXT_COLOR = "#1F2937";
    private static final String MUTED_COLOR = "#6B7280";
    private static final String BORDER_COLOR = "#E5E7EB";

    private EmailTemplates() {
    }

    static String otpEmail(String code, int expiryMinutes) {
        String body = """
            <div style="text-align: center; padding: 10px 0;">
                <h2 style="margin: 0 0 16px 0; color: %1$s; font-size: 22px; font-weight: 700; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">Verification Code</h2>
                <p style="margin: 0 0 24px 0; color: %1$s; font-size: 16px; line-height: 1.6;">
                    Use the code below to verify your request.
                </p>
                <div style="background-color: #F0FDFA; border: 2px dashed %2$s; border-radius: 12px; padding: 20px; margin: 24px auto; max-width: 280px;">
                    <span style="font-size: 36px; font-weight: 800; letter-spacing: 6px; color: %2$s; font-family: 'Courier New', Courier, monospace;">%3$s</span>
                </div>
                <p style="margin: 24px 0 0 0; color: %4$s; font-size: 13px; line-height: 1.5; font-style: italic;">
                    This code expires in %5$d minutes. If you did not request this code, you can safely disregard this message.
                </p>
            </div>
            """.formatted(TEXT_COLOR, BRAND_COLOR, code, MUTED_COLOR, expiryMinutes);

        return wrap("Verification Code", body);
    }

    static String welcomeEmail(String firstName, String accountNumber) {
        String body = """
            <div>
                <h2 style="margin: 0 0 16px 0; color: %1$s; font-size: 22px; font-weight: 700;">Welcome to Deen Wallet, %2$s!</h2>
                <p style="margin: 0 0 16px 0; color: %3$s; font-size: 15px; line-height: 1.6;">
                    Your account profile is active and ready for use.
                </p>
                <p style="margin: 0 0 24px 0; color: %3$s; font-size: 15px; line-height: 1.6;">
                    You can convert money between mobile money services right from the app. Keep your account identifier handy for customer care inquiries.
                </p>
                <div style="background-color: #F9FAFB; border: 1px solid %4$s; border-radius: 12px; padding: 20px; margin: 24px 0; text-align: center;">
                    <span style="display: block; font-size: 12px; color: %5$s; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; font-weight: 600;">Your Account Identifier</span>
                    <span style="font-size: 20px; font-weight: 700; color: %6$s; font-family: monospace; letter-spacing: 1px;">%7$s</span>
                </div>
            </div>
            """.formatted(TEXT_COLOR, escape(firstName), TEXT_COLOR, BORDER_COLOR, MUTED_COLOR, BRAND_COLOR, accountNumber);

        return wrap("Welcome to Deen Wallet", body);
    }

    static String accountActivatedEmail(String firstName) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Account Profile Notice</h2>
                </div>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    This email confirms that your Deen Wallet account status is active.
                </p>
                
                <p style="margin: 0 0 24px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    You may sign in and manage your wallet services normally.
                </p>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                escape(firstName)
        );

        return wrap("Account Profile Notice", body);
    }

    static String accountDeactivatedEmail(String firstName, String supportEmail) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Account Service Summary</h2>
                </div>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    An administrative update was made to your Deen Wallet profile. Standard automated processing is currently inactive.
                </p>
                
                <div style="background-color: #F9FAFB; border-left: 4px solid %4$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        For questions regarding your service profile, contact support at <strong style="color: %1$s;">%5$s</strong>.
                    </p>
                </div>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                escape(firstName),
                BRAND_COLOR,
                supportEmail
        );

        return wrap("Account Service Summary", body);
    }

    static String accountLockedEmail(String firstName, String lockReason, String supportEmail) {
        String detailMessage;
        if ("pin".equalsIgnoreCase(lockReason)) {
            detailMessage = "The maximum threshold for transaction PIN entries was reached.";
        } else if ("password".equalsIgnoreCase(lockReason)) {
            detailMessage = "The maximum threshold for sign-in attempts was reached.";
        } else {
            detailMessage = "A limit was reached for entries on your account profile.";
        }

        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Service Activity Summary</h2>
                </div>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    %4$s
                </p>
                
                <p style="margin: 0 0 24px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Further automated attempts are temporarily limited for this profile session.
                </p>
                
                <div style="background-color: #F9FAFB; border-left: 4px solid %5$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        If you require help with your account, please reach out to customer support at <strong style="color: %1$s;">%6$s</strong>.
                    </p>
                </div>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                escape(firstName),
                detailMessage,
                BRAND_COLOR,
                supportEmail
        );

        return wrap("Service Activity Summary", body);
    }

    static String adminLoginBlockedEmail(String firstName, boolean superAdmin, String supportEmail) {
        String roleLabel = superAdmin ? "Super Administrator" : "Administrator";

        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Administrative Activity Notice</h2>
                </div>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    This is an automated status notice for your %4$s profile on Deen Wallet.
                </p>
                
                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    The entry threshold for administrative sign-in was reached. System sign-in functions are temporarily off for this account.
                </p>
                
                <div style="background-color: #F9FAFB; border-left: 4px solid %5$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        To update your administrative settings, please contact support at <strong style="color: %1$s;">%6$s</strong>.
                    </p>
                </div>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                escape(firstName),
                roleLabel,
                BRAND_COLOR,
                supportEmail
        );

        return wrap("Administrative Activity Notice", body);
    }

    static String transactionCompletedEmail(String transactionCode, String amount, String totalCharged,
                                            String recipientName, String recipientPhone, String recipientProvider) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Transfer Receipt</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F0FDFA; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>
                
                <div style="border: 1px solid %4$s; border-radius: 12px; overflow: hidden; margin-bottom: 24px;">
                    <table cellpadding="0" cellspacing="0" style="width: 100%%; border-collapse: collapse;">
                        %5$s
                        %6$s
                        %7$s
                        %8$s
                        %9$s
                    </table>
                </div>
                
                <p style="margin: 0; color: %2$s; font-size: 13px; line-height: 1.5; text-align: center;">
                    Thank you for using Deen Wallet.
                </p>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                transactionCode,
                BORDER_COLOR,
                row("Amount sent", "SLE " + amount),
                row("Total charged", "SLE " + totalCharged),
                row("Operator", providerLabel(recipientProvider)),
                row("Recipient name", escape(recipientName)),
                row("Recipient number", maskTail(recipientPhone))
        );

        return wrap("Transfer Receipt", body);
    }

    static String transactionFailedEmail(String transactionCode, String amount, String recipientPhone, String failureReason) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Transfer Status Update</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F3F4F6; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>
                
                <p style="margin: 0 0 20px 0; color: %4$s; font-size: 15px; line-height: 1.6; text-align: center;">
                    Your transfer of <strong style="color: %4$s;">SLE %5$s</strong> to <strong style="color: %4$s;">%6$s</strong> was not completed.
                </p>
                
                <div style="background-color: #F9FAFB; border-left: 4px solid %1$s; padding: 16px; border-radius: 4px; margin-bottom: 16px;">
                    <p style="margin: 0 0 8px; color: %4$s; font-size: 14px; line-height: 1.5;">
                        <strong>Details:</strong> %7$s
                    </p>
                    <p style="margin: 0; color: %4$s; font-size: 14px; line-height: 1.5;">
                        <strong>Status:</strong> If any funds were debited, they will automatically reflect in your balance.
                    </p>
                </div>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                transactionCode,
                TEXT_COLOR,
                amount,
                maskTail(recipientPhone),
                escape(failureReason == null || failureReason.isBlank() ? "The transaction was not processed by the operator provider." : failureReason)
        );

        return wrap("Transfer Status Update", body);
    }

    private static String row(String label, String value) {
        return """
            <tr style="border-bottom: 1px solid %1$s;">
                <td style="padding: 14px 16px; font-size: 14px; color: %2$s; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">%3$s</td>
                <td style="padding: 14px 16px; font-size: 14px; font-weight: 600; color: %4$s; text-align: right; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">%5$s</td>
            </tr>
            """.formatted(BORDER_COLOR, MUTED_COLOR, label, TEXT_COLOR, value);
    }

    private static String providerLabel(String providerId) {
        if ("m17".equals(providerId)) {
            return "Orange Money";
        }
        if ("m18".equals(providerId)) {
            return "Africell Money";
        }
        return providerId == null ? "Unknown" : providerId;
    }

    private static String maskTail(String phone) {
        if (phone == null || phone.length() < 4) {
            return phone;
        }
        return phone.substring(0, phone.length() - 4) + "****";
    }

    private static String escape(String value) {
        return value == null ? "" : value
                                    .replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;");
    }

    private static String wrap(String title, String bodyHtml) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%1$s</title>
            </head>
            <body style="margin: 0; padding: 0; width: 100%% !important; background-color: #F3F4F6; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased;">
                <table cellpadding="0" cellspacing="0" style="width: 100%%; background-color: #F3F4F6; padding: 32px 16px;">
                    <tr>
                        <td align="center">
                            <table cellpadding="0" cellspacing="0" style="max-width: 540px; width: 100%%; background-color: #FFFFFF; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); border: 1px solid #E5E7EB;">
                                <!-- Styled Text Header -->
                                <tr>
                                    <td style="background-color: %1$s; padding: 24px; text-align: center;">
                                        <span style="color: #FFFFFF; font-size: 24px; font-weight: 800; letter-spacing: 1px; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">DEEN WALLET</span>
                                    </td>
                                </tr>
                                <!-- Body -->
                                <tr>
                                    <td style="padding: 32px 24px; background-color: #FFFFFF;">
                                        %2$s
                                    </td>
                                </tr>
                                <!-- Footer -->
                                <tr>
                                    <td style="padding: 24px; background-color: #F9FAFB; border-top: 1px solid %3$s; text-align: center;">
                                        <p style="margin: 0 0 6px 0; color: %4$s; font-size: 12px; font-weight: 600;">DEEN WALLET LTD</p>
                                        <p style="margin: 0 0 10px 0; color: %4$s; font-size: 11px;">Safe and instant conversions between mobile money services.</p>
                                        <p style="margin: 0; color: %4$s; font-size: 11px; font-style: italic; border-top: 1px dashed %3$s; padding-top: 8px;">This is an automated notification. Please do not reply directly to this message.</p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """.formatted(BRAND_COLOR, bodyHtml, BORDER_COLOR, MUTED_COLOR);
    }
}