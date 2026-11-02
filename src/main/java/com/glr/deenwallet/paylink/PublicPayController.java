package com.glr.deenwallet.paylink;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.UUID;

/**
 * The public side of a payment link: the page people open (/p/{slug}) and the three calls it makes. No sign-in.
 * Protected against abuse by the per-IP limits in ApiRateLimitFilter and by PaymentRequestService.
 */
@Controller
public class PublicPayController {

    private final PaymentRequestService service;

    public PublicPayController(PaymentRequestService service) {
        this.service = service;
    }

    /** The shareable address. The page reads the code from the address bar. */
    @GetMapping("/p/{slug}")
    public String page(@PathVariable String slug) {
        return "forward:/pay.html";
    }

    @GetMapping("/api/public/pay/{slug}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> view(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publicView(slug));
    }

    @PostMapping("/api/public/pay/{slug}/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> start(@PathVariable String slug, @RequestBody PaymentRequestService.StartRequest body,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.startPayment(slug, request.getRemoteAddr(), body));
    }

    @GetMapping("/api/public/pay/{slug}/status/{paymentId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status(@PathVariable String slug, @PathVariable UUID paymentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publicStatus(slug, paymentId));
    }
}
