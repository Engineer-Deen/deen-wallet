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
                <h2 style="margin: 0 0 16px 0; color: %1$s; font-size: 22px; font-weight: 700; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">Verify your request</h2>
                <p style="margin: 0 0 24px 0; color: %1$s; font-size: 16px; line-height: 1.6;">
                    Enter the code below to continue. For your security, never share this code with anyone, including Deen Wallet staff.
                </p>
                <div style="background-color: #F0FDFA; border: 2px dashed %2$s; border-radius: 12px; padding: 20px; margin: 24px auto; max-width: 280px;">
                    <span style="font-size: 36px; font-weight: 800; letter-spacing: 6px; color: %2$s; font-family: 'Courier New', Courier, monospace;">%3$s</span>
                </div>
                <p style="margin: 24px 0 0 0; color: %4$s; font-size: 13px; line-height: 1.5;">
                    This code expires in %5$d minutes. If you didn't request it, you can safely ignore this email — your account is still secure.
                </p>
            </div>
            """.formatted(TEXT_COLOR, BRAND_COLOR, code, MUTED_COLOR, expiryMinutes);

        return wrap("Verify your request", body);
    }

    // Backward-compatible overload for any existing callers that still use the original signature.
    static String welcomeEmail(String firstName, String accountNumber) {
        return welcomeEmail(firstName, accountNumber, "ceofeedback@deenwallapp.com");
    }

    static String welcomeEmail(String firstName, String accountNumber, String feedbackEmail) {
        String body = """
            <div>
                <h2 style="margin: 0 0 16px 0; color: %1$s; font-size: 22px; font-weight: 700;">Welcome to Deen Wallet, %2$s 👋</h2>
                <p style="margin: 0 0 16px 0; color: %3$s; font-size: 15px; line-height: 1.6;">
                    Your email is verified and your account is now active. You're all set to start sending and converting money between mobile money providers — fast, secure, and without the usual back-and-forth.
                </p>
                <p style="margin: 0 0 20px 0; color: %3$s; font-size: 15px; line-height: 1.6;">
                    Here's what you can do with your account:
                </p>
                <table cellpadding="0" cellspacing="0" style="width: 100%%; margin: 0 0 24px 0;">
                    <tr>
                        <td style="padding: 0 0 12px 0; color: %3$s; font-size: 14px; line-height: 1.5; vertical-align: top; width: 28px;">⚡</td>
                        <td style="padding: 0 0 12px 0; color: %3$s; font-size: 14px; line-height: 1.5;">Convert instantly between Orange Money and Africell, with no need for either side to switch networks.</td>
                    </tr>
                    <tr>
                        <td style="padding: 0 0 12px 0; color: %3$s; font-size: 14px; line-height: 1.5; vertical-align: top;">🔒</td>
                        <td style="padding: 0 0 12px 0; color: %3$s; font-size: 14px; line-height: 1.5;">Every transfer is protected with PIN confirmation and a traceable reference code.</td>
                    </tr>
                    <tr>
                        <td style="padding: 0; color: %3$s; font-size: 14px; line-height: 1.5; vertical-align: top;">👥</td>
                        <td style="padding: 0; color: %3$s; font-size: 14px; line-height: 1.5;">Save the people you send to often, so your next transfer takes seconds.</td>
                    </tr>
                </table>
                <div style="background-color: #F9FAFB; border: 1px solid %4$s; border-radius: 12px; padding: 20px; margin: 0 0 24px 0; text-align: center;">
                    <span style="display: block; font-size: 12px; color: %5$s; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; font-weight: 600;">Your Account Identifier</span>
                    <span style="font-size: 20px; font-weight: 700; color: %6$s; font-family: monospace; letter-spacing: 1px;">%7$s</span>
                </div>
                <p style="margin: 0; color: %3$s; font-size: 14px; line-height: 1.6;">
                    Have a question, a suggestion, or just want to tell us how we're doing? We read every message — reach the team directly at
                    <a href="mailto:%8$s" style="color: %1$s; font-weight: 600; text-decoration: none;">%8$s</a>.
                </p>
            </div>
            """.formatted(TEXT_COLOR, escape(firstName), TEXT_COLOR, BORDER_COLOR, MUTED_COLOR, BRAND_COLOR, accountNumber, feedbackEmail);

        return wrap("Welcome to Deen Wallet", body);
    }

    static String passwordResetEmail(String firstName, String resetLink) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Reset your password</h2>
                </div>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    We received a request to reset the password for your Deen Wallet account.
                </p>

                <p style="margin: 0 0 24px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Use the button below to choose a new password. This link expires in 30 minutes and can only be used once.
                </p>

                <div style="text-align: center; margin: 28px 0;">
                    <a href="%4$s"
                       style="display: inline-block; background-color: %1$s; color: #FFFFFF; text-decoration: none; font-size: 15px; font-weight: 700; padding: 13px 24px; border-radius: 8px;">
                        Reset Password
                    </a>
                </div>

                <div style="background-color: #F9FAFB; border-left: 4px solid %1$s; padding: 16px; border-radius: 4px; margin: 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 13px; line-height: 1.5;">
                        If you didn't request a password reset, you can safely ignore this email. Your current password will remain unchanged.
                    </p>
                </div>

                <p style="margin: 0; color: %2$s; font-size: 12px; line-height: 1.5; word-break: break-all;">
                    If the button doesn't work, copy and paste this link into your browser:<br>
                    <a href="%4$s" style="color: %1$s; text-decoration: none;">%4$s</a>
                </p>
            </div>
            """.formatted(
                BRAND_COLOR,
                MUTED_COLOR,
                escape(firstName),
                escape(resetLink)
        );

        return wrap("Reset your password", body);
    }

    static String accountActivatedEmail(String firstName) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Your account is active again</h2>
                </div>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Good news — your Deen Wallet account has been reactivated and is in good standing.
                </p>

                <p style="margin: 0 0 24px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    You can sign in and use your wallet as usual, right away.
                </p>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                escape(firstName)
        );

        return wrap("Your account is active again", body);
    }

    static String accountDeactivatedEmail(String firstName, String supportEmail) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Your account has been paused</h2>
                </div>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    A member of our team has temporarily paused your Deen Wallet account. While it's paused, you won't be able to sign in or send transfers.
                </p>

                <div style="background-color: #F9FAFB; border-left: 4px solid %4$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        If you'd like to understand why, or need this resolved, our support team is ready to help at <strong style="color: %1$s;">%5$s</strong>.
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

        return wrap("Your account has been paused", body);
    }

    static String accountLockedEmail(String firstName, String lockReason, String supportEmail) {
        String detailMessage;
        if ("pin".equalsIgnoreCase(lockReason)) {
            detailMessage = "We noticed several incorrect PIN attempts in a row, so we've temporarily locked PIN confirmation on your account to keep it safe.";
        } else if ("password".equalsIgnoreCase(lockReason)) {
            detailMessage = "We noticed several unsuccessful sign-in attempts in a row, so we've temporarily locked sign-in on your account to keep it safe.";
        } else {
            detailMessage = "We noticed unusual activity on your account, so we've temporarily placed a hold on it as a precaution.";
        }

        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">A quick security update</h2>
                </div>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    %4$s
                </p>

                <p style="margin: 0 0 24px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    This is a routine protection measure. Further attempts will stay restricted for a short while before you can try again.
                </p>

                <div style="background-color: #F9FAFB; border-left: 4px solid %5$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        Wasn't you, or need a hand getting back in sooner? Reach our support team at <strong style="color: %1$s;">%6$s</strong>.
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

        return wrap("A quick security update", body);
    }

    static String adminLoginBlockedEmail(String firstName, boolean superAdmin, String supportEmail) {
        String roleLabel = superAdmin ? "Super Administrator" : "Administrator";

        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Admin sign-in temporarily locked</h2>
                </div>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    Hello %3$s,
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    This is a security notice for your %4$s account on Deen Wallet.
                </p>

                <p style="margin: 0 0 16px 0; color: %2$s; font-size: 15px; line-height: 1.6;">
                    We detected repeated unsuccessful sign-in attempts, so administrative sign-in has been temporarily disabled on this account as a precaution.
                </p>

                <div style="background-color: #F9FAFB; border-left: 4px solid %5$s; padding: 16px; border-radius: 4px; margin: 20px 0 24px 0;">
                    <p style="margin: 0; color: %2$s; font-size: 14px; line-height: 1.5;">
                        To restore access, please contact support at <strong style="color: %1$s;">%6$s</strong>.
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

        return wrap("Admin sign-in temporarily locked", body);
    }

    static String transactionCompletedEmail(String transactionCode, String amount, String totalCharged,
                                            String recipientName, String recipientPhone, String recipientProvider,
                                            boolean bankTransfer, String bankName, String bankAccountNumber) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Transfer complete ✅</h2>
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
                    Keep this reference handy in case you ever need to look up this transfer. Thank you for choosing Deen Wallet.
                </p>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                transactionCode,
                BORDER_COLOR,
                row("Amount sent", "SLE " + amount),
                row("Total charged", "SLE " + totalCharged),
                bankTransfer ? row("Destination bank", escape(bankName)) : row("Operator", providerLabel(recipientProvider)),
                row("Recipient name", escape(recipientName)),
                bankTransfer ? row("Bank account", maskTail(bankAccountNumber)) : row("Recipient number", maskTail(recipientPhone))
        );

        return wrap("Transfer complete", body);
    }

    static String transactionFailedEmail(String transactionCode, String amount, String recipientPhone, String failureReason,
                                         boolean bankTransfer, String bankName, String bankAccountNumber, String bankHolderName) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">We couldn't complete your transfer</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F3F4F6; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>

                <p style="margin: 0 0 20px 0; color: %4$s; font-size: 15px; line-height: 1.6; text-align: center;">
                    Your transfer of <strong style="color: %4$s;">SLE %5$s</strong> to <strong style="color: %4$s;">%6$s</strong> didn't go through.
                </p>

                <div style="background-color: #F9FAFB; border-left: 4px solid %1$s; padding: 16px; border-radius: 4px; margin-bottom: 16px;">
                    <p style="margin: 0 0 8px; color: %4$s; font-size: 14px; line-height: 1.5;">
                        <strong>What happened:</strong> %7$s
                    </p>
                    <p style="margin: 0; color: %4$s; font-size: 14px; line-height: 1.5;">
                        <strong>Your money:</strong> %8$s
                    </p>
                </div>
            </div>
            """.formatted(
                TEXT_COLOR,
                MUTED_COLOR,
                transactionCode,
                TEXT_COLOR,
                amount,
                bankTransfer
                        ? escape((bankName == null ? "the selected bank" : bankName) + " account " + maskTail(bankAccountNumber))
                        : maskTail(recipientPhone),
                escape(failureReason == null || failureReason.isBlank() ? "The operator was unable to process this transaction." : failureReason),
                bankTransfer
                        ? "If your mobile-money payment was received but the bank payout failed, please contact Deen Wallet Support so the transaction can be reconciled."
                        : "If any amount was deducted, it will be automatically returned through the payment process."
        );

        return wrap("We couldn't complete your transfer", body);
    }

    // ---------------- Failed payout / refund emails ----------------
    static String payoutFailedEmail(String transactionCode, String amount, String totalCharged,
                                    String recipientLabel, String reason, String payerPhone) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">We couldn't deliver your transfer</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F3F4F6; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>
                <p style="margin: 0 0 20px 0; color: %1$s; font-size: 15px; line-height: 1.6; text-align: center;">
                    Your payment of <strong>SLE %4$s</strong> was received, but the transfer of <strong>SLE %5$s</strong> to <strong>%6$s</strong> could not be completed.
                </p>
                <div style="background-color: #F9FAFB; border-left: 4px solid %1$s; padding: 16px; border-radius: 4px; margin-bottom: 16px;">
                    <p style="margin: 0 0 8px; color: %1$s; font-size: 14px; line-height: 1.5;"><strong>Why it failed:</strong> %7$s</p>
                    <p style="margin: 0 0 8px; color: %1$s; font-size: 14px; line-height: 1.5;"><strong>Your money is safe.</strong> It is being held by Deen Wallet and has not been lost.</p>
                    <p style="margin: 0; color: %1$s; font-size: 14px; line-height: 1.5;"><strong>What happens next:</strong> our support team will either retry the transfer or refund SLE %4$s to the number you paid from (%8$s).</p>
                </div>
                <p style="margin: 0; color: %2$s; font-size: 13px; line-height: 1.5; text-align: center;">
                    To speed things up, contact Deen Wallet Support and quote reference <strong>%3$s</strong>.
                </p>
            </div>
            """.formatted(TEXT_COLOR, MUTED_COLOR, escape(transactionCode), escape(totalCharged), escape(amount),
                escape(recipientLabel),
                escape(reason == null || reason.isBlank() ? "The provider was unable to process this transfer." : reason),
                escape(maskTail(payerPhone)));
        return wrap("We couldn't deliver your transfer", body);
    }

    static String refundInitiatedEmail(String transactionCode, String totalCharged, String payerPhone) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Your refund is on its way</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F3F4F6; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>
                <p style="margin: 0; color: %1$s; font-size: 15px; line-height: 1.6; text-align: center;">
                    We are refunding <strong>SLE %4$s</strong> to <strong>%5$s</strong>, the number you paid from. You will get another email when it arrives.
                </p>
            </div>
            """.formatted(TEXT_COLOR, MUTED_COLOR, escape(transactionCode), escape(totalCharged), escape(maskTail(payerPhone)));
        return wrap("Your refund is on its way", body);
    }

    static String refundCompletedEmail(String transactionCode, String totalCharged, String payerPhone) {
        String body = """
            <div>
                <div style="text-align: center; margin-bottom: 24px;">
                    <h2 style="margin: 0; color: %1$s; font-size: 22px; font-weight: 700;">Refund sent</h2>
                    <p style="margin: 6px 0 0 0; color: %2$s; font-size: 14px;">
                        Reference: <span style="font-family: monospace; font-weight: 600; background: #F3F4F6; padding: 2px 8px; border-radius: 4px;">%3$s</span>
                    </p>
                </div>
                <p style="margin: 0; color: %1$s; font-size: 15px; line-height: 1.6; text-align: center;">
                    <strong>SLE %4$s</strong> has been refunded to <strong>%5$s</strong>. Thank you for your patience, and we are sorry for the inconvenience.
                </p>
            </div>
            """.formatted(TEXT_COLOR, MUTED_COLOR, escape(transactionCode), escape(totalCharged), escape(maskTail(payerPhone)));
        return wrap("Refund sent", body);
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
                                <!-- Header: text-only branding to keep the email attachment-free -->
                                <tr>
                                    <td style="background-color: %2$s; padding: 22px 24px; text-align: center;">
                                        <div style="font-size: 22px; line-height: 1.2; font-weight: 800; letter-spacing: 1px; color: #FFFFFF; font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;">
                                            DEEN WALLET
                                        </div>
                                    </td>
                                </tr>
                                <!-- Body -->
                                <tr>
                                    <td style="padding: 32px 24px; background-color: #FFFFFF;">
                                        %3$s
                                    </td>
                                </tr>
                                <!-- Footer -->
                                <tr>
                                    <td style="padding: 24px; background-color: #F9FAFB; border-top: 1px solid %4$s; text-align: center;">
                                        <p style="margin: 0 0 6px 0; color: %5$s; font-size: 12px; font-weight: 600;">DEEN WALLET LTD</p>
                                        <p style="margin: 0 0 10px 0; color: %5$s; font-size: 11px;">Safe and instant conversions between mobile money services.</p>
                                        <p style="margin: 0 0 10px 0; color: %5$s; font-size: 11px;">Need help? Reach our support team at support@deenwallapp.com</p>
                                        <p style="margin: 0; color: %5$s; font-size: 11px; font-style: italic; border-top: 1px dashed %4$s; padding-top: 8px;">This is an automated message from an unmonitored mailbox — please don't reply directly to this email.</p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """.formatted(title, BRAND_COLOR, bodyHtml, BORDER_COLOR, MUTED_COLOR);
    }
}
