/*!
 *
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 *
 * Copyright (c) 2002-2024 Hitachi Vantara. All rights reserved.
 *
 */

package org.pentaho.platform.web.servlet;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.pentaho.di.core.util.HttpClientManager;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.util.messages.LocaleHelper;
import org.pentaho.platform.web.servlet.messages.Messages;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WebttleServlet extends ServletBase {
  private static final Log logger = LogFactory.getLog( WebttleServlet.class );

  private static final long serialVersionUID = 4680027723733552639L;

  private static final String TRUST_USER_PARAM = "_TRUST_USER_";

  private String proxyURL = null; // "http://localhost:8080/pentaho";

  private String redirectURL = null;

  private String errorURL = null; // The URL to redirect to if the user is invalid

  /**
   * Base Constructor
   */
  public WebttleServlet() {
    super();
  }

  @Override
  public Log getLogger() {
    return WebttleServlet.logger;
  }

  @Override
  public void init( final ServletConfig servletConfig ) throws ServletException {
    redirectURL = servletConfig.getInitParameter( "RedirectURL" ); //$NON-NLS-1$
    if (  redirectURL == null  ) {
      error( Messages.getInstance().getString( "ProxyServlet.ERROR_0001_NO_PROXY_URL_SPECIFIED" ) ); //$NON-NLS-1$
    } else {
      try {
        URL url = new URL( redirectURL.trim() ); // Just doing this to verify
        // it's good
        info( Messages.getInstance().getString( "ProxyServlet.INFO_0001_URL_SELECTED",
          url.toExternalForm() ) ); // using 'url' to get rid of unused var compiler warning //$NON-NLS-1$
      } catch ( Throwable t ) {
        error( Messages.getInstance().getErrorString( "ProxyServlet.ERROR_0002_INVALID_URL", redirectURL ) ); //$NON-NLS-1$
        redirectURL = null;
      }
    }

    errorURL = servletConfig.getInitParameter( "ErrorURL" ); //$NON-NLS-1$
    super.init( servletConfig );
  }

  protected void doProxy( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
    // Got nothing from web.xml.
    if ( redirectURL == null ) {
      return;
    }

    PentahoSystem.systemEntryPoint();
    try {
      // Get the user from the session
      IPentahoSession userSession = getPentahoSession( request );
      String userName = userSession != null ? userSession.getName() : null;
      if ( StringUtils.isEmpty( userName ) && StringUtils.isNotBlank( errorURL ) ) {
        response.sendRedirect( errorURL );
        return;
      }

      URI requestUri = buildProxiedUri( request, userName );

      doProxyCore( requestUri, request, response );

    } catch ( URISyntaxException e ) {
      error( Messages.getInstance().getErrorString( "ProxyServlet.ERROR_0006_URI_SYNTAX_EXCEPTION", e.getMessage() ) );
      e.printStackTrace();
    } finally {
      PentahoSystem.systemExitPoint();
    }
  }

  protected URI buildProxiedUri( final HttpServletRequest request, final String userName ) throws URISyntaxException {

    String baseUri = proxyURL + request.getRequestURI().replace( "/pentaho/webttle","" );
    URIBuilder uriBuilder = new URIBuilder( baseUri );

    List<NameValuePair> queryParams = uriBuilder.isQueryEmpty() ? new ArrayList<>() : uriBuilder.getQueryParams();

    // Just in case someone is trying to spoof the proxy.
    queryParams.removeIf( pair -> pair.getName().equals( TRUST_USER_PARAM ) );

    // Copy the parameters from the request to the proxy.
    Map<String, String[]> paramMap = request.getParameterMap();
    for ( Map.Entry<String, String[]> entry : paramMap.entrySet() ) {
      for ( String element : entry.getValue() ) {
        queryParams.add( new BasicNameValuePair( entry.getKey(), element ) );
      }
    }

    // Add the trusted user from the session
    if ( StringUtils.isNotEmpty( userName ) ) {
      queryParams.add( new BasicNameValuePair( TRUST_USER_PARAM, userName ) );
    }

    uriBuilder.setParameters( queryParams );

    debug( Messages.getInstance().getString( "ProxyServlet.DEBUG_0001_OUTPUT_URL", uriBuilder.toString() ) );

    return uriBuilder.build();
  }

  protected void doProxyCore( final URI requestUri, final HttpServletRequest request, final HttpServletResponse response )
    throws IOException {

    try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
      HttpRequestBase method = null;

      if ("POST".equals(request.getMethod())) {
        HttpPost post = new HttpPost(requestUri);
        post.setHeader("Content-Type", "application/json");
        String requestBody = IOUtils.toString(request.getReader());
        System.out.println("RequestBody :" + requestBody);
        StringEntity stringEntity = new StringEntity(requestBody, ContentType.APPLICATION_JSON);
        post.setEntity(stringEntity);
        method = (HttpPost) post;
      } else if ("GET".equals(request.getMethod())) {
        method = new HttpGet(requestUri);
        if (requestUri.toString().contains("redirect")) {
          redirectToURL(request, response);
        }
      }

      try {
        // Execute the method.
        HttpResponse httpResponse = httpclient.execute(method);
        StatusLine statusLine = httpResponse.getStatusLine();
        int statusCode = statusLine.getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
          error(Messages.getInstance().getErrorString(
                  "ProxyServlet.ERROR_0003_REMOTE_HTTP_CALL_FAILED", statusLine.toString())); //$NON-NLS-1$
          return;
        }

        HttpEntity httpResponseEntity = httpResponse.getEntity();
        response.setContentType(httpResponseEntity.getContentType().toString()); //$NON-NLS-1$
        response.setContentLength(Math.toIntExact(httpResponseEntity.getContentLength())); //$NON-NLS-1$

        String responseBody = EntityUtils.toString(httpResponseEntity);
        response.getOutputStream().write(responseBody.getBytes());

        System.out.println("HttpMethod :" + method);
        System.out.println("Proxy Response :" + responseBody);
      } catch (IOException e) {
        error(Messages.getInstance().getErrorString("ProxyServlet.ERROR_0005_TRANSPORT_FAILURE"), e); //$NON-NLS-1$
      }
    }
  }

  private void redirectToURL(HttpServletRequest request, HttpServletResponse response) throws IOException {
    Cookie cookie = new Cookie("JSESSIONID", request.getSession().getId());
    cookie.setMaxAge(request.getSession().getMaxInactiveInterval());
    cookie.setPath("/pentaho");
    response.addCookie(cookie);
    response.sendRedirect(redirectURL);
  }

  @Override
  protected void service( final HttpServletRequest arg0, final HttpServletResponse arg1 ) throws ServletException,
    IOException {
    // TODO Auto-generated method stub
    super.service( arg0, arg1 );
  }

  @Override
  protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
    throws ServletException, IOException {
    doProxy( request, response );
  }

  @Override
  protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws ServletException,
    IOException {
    doProxy( request, response );
  }


  public String getProxyURL() {
    return proxyURL;
  }

  public String getRedirectURL() {
    return redirectURL;
  }

  public String getErrorURL() {
    return errorURL;
  }
}
