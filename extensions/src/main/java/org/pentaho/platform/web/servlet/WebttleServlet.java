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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;

public class WebttleServlet extends ServletBase {
  private static final Log logger = LogFactory.getLog( WebttleServlet.class );

  private static final long serialVersionUID = 4680027723733552639L;

  private String redirectURL = null;

  private String errorURL = null; // The URL to redirect to if the user is invalid

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
      error( "No redirect host URL specified in the web.xml file" ); //$NON-NLS-1$
    } else {
      try {
        URL url = new URL( redirectURL.trim() );
        info( String.format( "Setting proxy URL to: [%s]", url.toExternalForm()) ); // using 'url' to get rid of unused var compiler warning //$NON-NLS-1$
      } catch ( Throwable t ) {
        error( String.format( "Invalid proxy host URL specified: [%s]", redirectURL) ); //$NON-NLS-1$
        redirectURL = null;
      }
    }

    errorURL = servletConfig.getInitParameter( "ErrorURL" ); //$NON-NLS-1$
  }

  @Override
  protected void service( final HttpServletRequest arg0, final HttpServletResponse arg1 ) throws ServletException,
    IOException {
    // TODO Auto-generated method stub
    super.service( arg0, arg1 );
  }

  @Override
  protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws ServletException,
    IOException {
    if(request.getRequestURI().contains("/webttle/redirect")){
      Cookie cookie = new Cookie("JSESSIONID", request.getSession().getId());
      cookie.setMaxAge(request.getSession().getMaxInactiveInterval());
      cookie.setPath("/");
      response.addCookie(cookie);
      response.sendRedirect(redirectURL);
    }
  }


  public String getRedirectURL() {
    return redirectURL;
  }

  public String getErrorURL() {
    return errorURL;
  }
}
