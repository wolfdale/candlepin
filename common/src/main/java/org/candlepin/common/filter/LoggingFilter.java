/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.common.filter;

import org.candlepin.common.logging.LoggingUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * LoggingFilter
 *
 * This class must be a Singleton as described in
 * <a href="http://code.google.com/p/google-guice/wiki/ServletModule#Filter_Mapping">
 * the Guice documentation</a>.
 */
@Component
@Order(3)
public class LoggingFilter implements Filter {

    private static Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    private static final int CSID_MAX_LENGTH = 40;
    private static final Pattern CSID_REGEX = Pattern.compile("^([a-zA-Z0-9-]){1,}$");

    private String customHeaderName;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        customHeaderName = filterConfig.getInitParameter("header.name");
    }

    @Override
    public void destroy() {
        customHeaderName = null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void doFilter(ServletRequest request, ServletResponse response,
        FilterChain chain) throws IOException, ServletException {

        long startTime = System.currentTimeMillis();
        TeeHttpServletRequest req = new TeeHttpServletRequest(
            (HttpServletRequest) request);
        TeeHttpServletResponse resp = new TeeHttpServletResponse(
            (HttpServletResponse) response);

        try {
            // Generate a UUID for this request and store in the thread local MDC.
            // Will be logged with every request if the ConversionPattern uses it.
            MDC.put(LoggingUtil.MDC_REQUEST_TYPE_KEY, "req");
            String requestUUID = UUID.randomUUID().toString();
            MDC.put(LoggingUtil.MDC_REQUEST_UUID_KEY, requestUUID);

            String correlationId = null;

            Enumeration<String> headerNames = (Enumeration<String>) ((HttpServletRequest) request)
                .getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = (String) headerNames.nextElement();
                if ("X-Correlation-ID".equalsIgnoreCase(name)) {
                    correlationId = ((HttpServletRequest) request).getHeader(name);
                }
            }

            if (correlationId != null) {
                if (CSID_REGEX.matcher(correlationId).matches()) {
                    if (correlationId.length() > CSID_MAX_LENGTH) {
                        log.warn("Correlation Id {} has more characters than {}, hence truncating to {}",
                            correlationId, CSID_MAX_LENGTH, CSID_MAX_LENGTH);
                        correlationId = correlationId.substring(0, CSID_MAX_LENGTH);
                    }
                    MDC.put(LoggingUtil.MDC_CSID_KEY, correlationId);
                }
                else {
                    log.warn("Correlation Id must consist of alphanumeric characters or hypens");
                }
            }

            // Add requestUuid to the serverRequest as an attribute, so Tomcat can
            // log it to the access log with "%{requestUuid}r"
            req.setAttribute("requestUuid", requestUUID);

            // Report the requestUuid to the client in the response.
            // Not sure this is useful yet.
            resp.setHeader(customHeaderName, requestUUID);

            if (log.isDebugEnabled()) {
                log.debug("{}", ServletLogger.logRequest(req));
            }
            else {
                log.info("{}", ServletLogger.logBasicRequestInfo(req));
            }

            chain.doFilter(req, resp);

            if (log.isDebugEnabled()) {
                log.debug("{}", ServletLogger.logResponse(resp, startTime));
            }
            else {
                log.info("{}", ServletLogger.logBasicResponseInfo(resp, startTime));
            }

            resp.finish();
        }
        finally {
            MDC.clear();
        }
    }
}
