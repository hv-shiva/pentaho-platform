/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.plugin.services.importer;

/**
 * Used by REST Services to handle mulit part form upload from Schema WorkBench 
 *
 * @author tband
 * @date 6/27/12
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;

import mondrian.util.Pair;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.eigenbase.xom.XOMException;
import org.eigenbase.xom.XOMUtil;
import org.pentaho.metadata.repository.DomainAlreadyExistsException;
import org.pentaho.metadata.repository.DomainIdNullException;
import org.pentaho.metadata.repository.DomainStorageException;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.mimetype.IMimeType;
import org.pentaho.platform.api.repository2.unified.IPlatformImportBundle;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryFileAcl;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.plugin.action.mondrian.catalog.IAclAwareMondrianCatalogService;
import org.pentaho.platform.plugin.action.mondrian.catalog.IMondrianCatalogService;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalog;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalogServiceException;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalogServiceException.Reason;
import org.xml.sax.SAXException;

public class MondrianImportHandler implements IPlatformImportHandler {

  protected static final String PARAMETERS = "parameters";

  public static final String ENABLE_XMLA = "EnableXmla";

  protected static final String DOMAIN_ID = "domain-id";

  protected static final String DATA_SOURCE = "DataSource";

  protected static final String PROVIDER = "Provider";

  protected static final String DEFAULT_PROVIDER = "mondrian";

  private List<IMimeType> mimeTypes;
  IMondrianCatalogService mondrianRepositoryImporter;

  public MondrianImportHandler( List<IMimeType> mimeTypes, final IMondrianCatalogService mondrianImporter ) {
    if ( mondrianImporter == null ) {
      throw new IllegalArgumentException();
    }
    this.mimeTypes = mimeTypes;
    this.mondrianRepositoryImporter = mondrianImporter;
  }

  /**
   * **************************************** Main entry point from the Spring Interface
   *
   * @param IPlatformImportBundle
   * @throws IOException
   * @throws DomainStorageException
   * @throws DomainAlreadyExistsException
   * @throws DomainIdNullException
   * @throws PlatformImportException
   * @throws SAXException
   * @throws ParserConfigurationException
   */

  public void importFile( IPlatformImportBundle bundle ) throws PlatformImportException, DomainIdNullException,
      DomainAlreadyExistsException, DomainStorageException, IOException {
    boolean overwriteInRepossitory = bundle.overwriteInRepository();
    boolean xmla = "false".equalsIgnoreCase( findParameterPropertyValue( bundle, ENABLE_XMLA ) ) ? false : true;
    final String domainId = (String) bundle.getProperty( DOMAIN_ID );

    if ( domainId == null ) {
      throw new PlatformImportException( "Bundle missing required domain-id property" );
    }
    try {
      InputStream is = bundle.getInputStream();
      MondrianCatalog catalog = this.createCatalogObject( domainId, xmla, bundle );
      IPentahoSession session = PentahoSessionHolder.getSession();

      // Validate if xml file is well-formed ( BISERVER-14716 and BISERVER-14717 )
      if ( !validateFileData( is ) ) {
        throw new Exception( "Bundle data is not valid" );
      }

      if ( mondrianRepositoryImporter instanceof IAclAwareMondrianCatalogService ) {
        RepositoryFileAcl acl = bundle.isApplyAclSettings() ? bundle.getAcl() : null;
        IAclAwareMondrianCatalogService aware = (IAclAwareMondrianCatalogService) mondrianRepositoryImporter;
        aware.addCatalog( is, catalog, overwriteInRepossitory, acl, session );
      } else {
        mondrianRepositoryImporter.addCatalog( is, catalog, overwriteInRepossitory, session );
      }
    } catch ( MondrianCatalogServiceException mse ) {
      int statusCode = convertExceptionToStatus( mse );
      throw new PlatformImportException( mse.getMessage(), statusCode );
    } catch ( Exception e ) {
      throw new PlatformImportException( e.getMessage(), PlatformImportException.PUBLISH_GENERAL_ERROR );
    }
  }

  /**
   *  Fix for: BISERVER-14716 and BISERVER-14717
   *  Helper method to try to parse file, if throw an exception, so file is not valid
   *
   * @param inputStream
   * @return
   * @throws XOMException
   *
   * @throws IOException
   */
  private boolean validateFileData( InputStream inputStream ) throws XOMException, IOException {
    String data = new String( IOUtils.toCharArray( inputStream ) );
    if ( !StringUtils.isEmpty( data ) ) {
      XOMUtil.createDefaultParser().parse( data );
      inputStream.reset();
      return true;
    }

    return false;
  }

  /**
   * helper method to find the value in the bundle from either the property or parameter list
   *
   * @param bundle
   * @param key
   * @return
   */
  private String findParameterPropertyValue( IPlatformImportBundle bundle, String key ) {
    String value = (String) bundle.getProperty( key );
    if ( value == null ) {
      mondrian.olap.Util.PropertyList propertyList =
          mondrian.olap.Util.parseConnectString( (String) bundle.getProperty( PARAMETERS ) );
      value = propertyList.get( key );
    }
    return value;
  }

  private Map<String, String> findParameters( IPlatformImportBundle bundle ) {
    mondrian.olap.Util.PropertyList propertyList =
        mondrian.olap.Util.parseConnectString( (String) bundle.getProperty( PARAMETERS ) );
    final Map<String, String> parameters = new HashMap<String, String>();
    for ( Pair<String, String> prop : propertyList ) {
      parameters.put( prop.left, prop.right );
    }
    return parameters;
  }

  /**
   * convert the catalog service exception to a platform exception and get the proper status code
   *
   * @param mse
   * @return
   */
  private int convertExceptionToStatus( MondrianCatalogServiceException mse ) {
    int statusCode = PlatformImportException.PUBLISH_TO_SERVER_FAILED;
    if ( mse.getReason().equals( Reason.GENERAL ) ) {
      statusCode = PlatformImportException.PUBLISH_GENERAL_ERROR;
    } else {
      if ( mse.getReason().equals( Reason.ACCESS_DENIED ) ) {
        statusCode = PlatformImportException.PUBLISH_TO_SERVER_FAILED;
      } else {
        if ( mse.getReason().equals( Reason.ALREADY_EXISTS ) ) {
          statusCode = PlatformImportException.PUBLISH_SCHEMA_EXISTS_ERROR;
        } else {
          if ( mse.getReason().equals( Reason.XMLA_SCHEMA_NAME_EXISTS ) ) {
            statusCode = PlatformImportException.PUBLISH_XMLA_CATALOG_EXISTS;
          }
        }
      }
    }
    return statusCode;
  }

  /**
   * Helper method to create a catalog object
   */
  protected MondrianCatalog createCatalogObject( String catName, boolean xmlaEnabled, IPlatformImportBundle bundle )
    throws ParserConfigurationException, SAXException, IOException, PlatformImportException {
    final Map<String, String> parameters = findParameters( bundle );
    final String dsName = findParameterPropertyValue( bundle, DATA_SOURCE );

    final String provider;
    if ( parameters.containsKey( PROVIDER ) ) {
      provider = findParameterPropertyValue( bundle, PROVIDER );
    } else {
      // Defaults to 'mondrian'
      provider = DEFAULT_PROVIDER;
    }

    StringBuilder sb = new StringBuilder();

    if ( dsName != null ) {
      sb.append( "DataSource=\"" )
        .append( StringEscapeUtils.escapeXml( dsName.replaceAll( "&quot;", "\"" ) ) )
        .append( "\";" );
    }
    if ( !parameters.containsKey( "EnableXmla" ) ) {
      sb.append( "EnableXmla=" )
        .append( xmlaEnabled )
        .append( ";" );
    }
    sb.append( "Provider=\"" )
      .append( StringEscapeUtils.escapeXml( provider.replaceAll( "&quot;", "\"" ) ) )
      .append( "\"" );

    // Build a list of the remaining properties
    for ( Entry<String, String> parameter : parameters.entrySet() ) {
      if ( !parameter.getKey().equals( DATA_SOURCE ) && !parameter.getKey().equals( PROVIDER ) ) {
        //value contains custom-escaped quotes.
        //It needs custom unescape and standard escapeXml for following mondrian parsing
        String parseSafeValue = StringEscapeUtils.escapeXml( parameter.getValue().replaceAll( "&quot;", "\"" ) );
        sb.append( ";" );
        sb.append( parameter.getKey() );
        sb.append( "=\"" );
        sb.append( parseSafeValue );
        sb.append( "\"" );
      }
    }

    MondrianCatalog catalog =
        new MondrianCatalog( catName, sb.toString(), provider + ":" + RepositoryFile.SEPARATOR + catName, null, null );

    return catalog;
  }

  @Override
  public List<IMimeType> getMimeTypes() {
    return mimeTypes;
  }
}
