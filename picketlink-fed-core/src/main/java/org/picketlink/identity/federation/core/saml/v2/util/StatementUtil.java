/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.picketlink.identity.federation.core.saml.v2.util;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import org.picketlink.identity.federation.core.constants.AttributeConstants;
import org.picketlink.identity.federation.core.saml.v2.constants.JBossSAMLURIConstants;
import org.picketlink.identity.federation.core.saml.v2.constants.X500SAMLProfileConstants;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AttributeStatementType;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AttributeStatementType.ASTChoiceType;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AttributeType;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AuthnContextClassRefType;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AuthnContextType;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AuthnContextType.AuthnContextTypeSequence;
import org.picketlink.identity.federation.newmodel.saml.v2.assertion.AuthnStatementType;

/**
 * Deals with SAML2 Statements
 * @author Anil.Saldhana@redhat.com
 * @since Aug 31, 2009
 */
public class StatementUtil
{
   public static final QName X500_QNAME = new QName(JBossSAMLURIConstants.X500_NSURI.get(), "Encoding", JBossSAMLURIConstants.X500_PREFIX.get());
   
   /**
    * Create an AuthnStatementType given the issue instant and the type of authentication
    * @param instant an instanceof {@link XMLGregorianCalendar}
    * @param authnContextClassRefValue indicate the type of authentication performed
    * @return {@link AuthnStatementType}
    */
   public static AuthnStatementType createAuthnStatement( XMLGregorianCalendar instant, String authnContextClassRefValue )
   {
      AuthnStatementType authnStatement = new AuthnStatementType( instant );
      
      AuthnContextType authnContext = new AuthnContextType();
      AuthnContextClassRefType authnContextClassRef = new AuthnContextClassRefType( URI.create( authnContextClassRefValue ));
      
      AuthnContextTypeSequence sequence = (authnContext).new AuthnContextTypeSequence();
      sequence.setClassRef( authnContextClassRef );
      authnContext.setSequence( sequence );
      
      authnStatement.setAuthnContext( authnContext );
      
      return authnStatement;
   }
   
   /**
    * Create an attribute statement with all the attributes
    * @param attributes a map with keys from {@link AttributeConstants}
    * @return
    */
   public static AttributeStatementType createAttributeStatement(Map<String, Object> attributes)
   {
      AttributeStatementType attrStatement = null;

      int i = 0;

      Set<String> keys = attributes.keySet();
      for (String key : keys)
      {
         if (i == 0)
         {
            //Deal with the X500 Profile of SAML2
            attrStatement = new AttributeStatementType(); 
            i++;
         }

         // if the attribute contains roles, add each role as an attribute.
         if (AttributeConstants.ROLES.equalsIgnoreCase(key))
         {
            Object value = attributes.get(key);
            if (value instanceof Collection<?>)
            {
               Collection<?> roles = (Collection<?>) value;
               for (Object role : roles)
               {
                  AttributeType roleAttr = new AttributeType( "Role" );
                  roleAttr.addAttributeValue(role);
                  attrStatement.addAttribute( new ASTChoiceType( roleAttr ));
               }
            }
         }

         else
         {
            AttributeType att;
            Object value = attributes.get(key);

            if (AttributeConstants.EMAIL_ADDRESS.equals(key))
            {
               att = getX500Attribute( X500SAMLProfileConstants.EMAIL_ADDRESS.get() );
               att.setFriendlyName(X500SAMLProfileConstants.EMAIL_ADDRESS.getFriendlyName()); 
            }
            else if (AttributeConstants.EMPLOYEE_NUMBER.equals(key))
            {
               att = getX500Attribute( X500SAMLProfileConstants.EMPLOYEE_NUMBER.get() );
               att.setFriendlyName(X500SAMLProfileConstants.EMPLOYEE_NUMBER.getFriendlyName()); 
            }
            else if (AttributeConstants.GIVEN_NAME.equals(key))
            {
               att = getX500Attribute( X500SAMLProfileConstants.GIVENNAME.get() );
               att.setFriendlyName(X500SAMLProfileConstants.GIVENNAME.getFriendlyName()); 
            }
            else if (AttributeConstants.TELEPHONE.equals(key))
            {
               att = getX500Attribute( X500SAMLProfileConstants.TELEPHONE.get() );
               att.setFriendlyName(X500SAMLProfileConstants.TELEPHONE.getFriendlyName());
               att.setName(X500SAMLProfileConstants.TELEPHONE.get());
            }
            else
               throw new RuntimeException( "Unknown:" + key );
            
            att.addAttributeValue( value );
            attrStatement.addAttribute( new ASTChoiceType( att ));
         }
      }
      return attrStatement;
   }

   /**
    * Given a set of roles, create an attribute statement
    * @param roles
    * @return
    */
   public static AttributeStatementType createAttributeStatement(List<String> roles)
   {
      AttributeStatementType attrStatement = new AttributeStatementType();
      for (String role : roles)
      {
         AttributeType attr = new AttributeType( "Role" );
         attr.addAttributeValue( role );
         attrStatement.addAttribute( new ASTChoiceType(  attr ));
      }
      return attrStatement;
   }

   private static AttributeType getX500Attribute( String name )
   {
      AttributeType att = new AttributeType( name );
      att.getOtherAttributes().put(X500_QNAME, "LDAP");

      att.setNameFormat(JBossSAMLURIConstants.ATTRIBUTE_FORMAT_URI.get());
      return att;
   }
}