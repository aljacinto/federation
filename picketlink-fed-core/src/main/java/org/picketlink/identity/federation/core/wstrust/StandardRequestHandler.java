/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
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
package org.picketlink.identity.federation.core.wstrust;

import java.net.URI;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.JAXBElement;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;

import org.apache.log4j.Logger;
import org.picketlink.identity.federation.core.exceptions.ProcessingException;
import org.picketlink.identity.federation.core.saml.v2.util.DocumentUtil;
import org.picketlink.identity.federation.core.util.XMLEncryptionUtil;
import org.picketlink.identity.federation.core.util.XMLSignatureUtil;
import org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken;
import org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityTokenResponse;
import org.picketlink.identity.federation.ws.policy.AppliesTo;
import org.picketlink.identity.federation.ws.trust.BinarySecretType;
import org.picketlink.identity.federation.ws.trust.ClaimsType;
import org.picketlink.identity.federation.ws.trust.EntropyType;
import org.picketlink.identity.federation.ws.trust.ObjectFactory;
import org.picketlink.identity.federation.ws.trust.RequestedProofTokenType;
import org.picketlink.identity.federation.ws.trust.RequestedSecurityTokenType;
import org.picketlink.identity.federation.ws.trust.RequestedTokenCancelledType;
import org.picketlink.identity.federation.ws.trust.StatusType;
import org.picketlink.identity.federation.ws.trust.UseKeyType;
import org.picketlink.identity.xmlsec.w3.xmldsig.KeyInfoType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * <p>
 * Default implementation of the {@code WSTrustRequestHandler} interface. It creates the request context containing the
 * original WS-Trust request as well as any information that may be relevant to the token processing, and delegates the
 * actual token handling processing to the appropriate {@code SecurityTokenProvider}.
 * </p>
 * 
 * @author <a href="mailto:sguilhen@redhat.com">Stefan Guilhen</a>
 */
public class StandardRequestHandler implements WSTrustRequestHandler
{
   private static Logger log = Logger.getLogger(StandardRequestHandler.class);

   private boolean trace = log.isTraceEnabled();

   private static long KEY_SIZE = 128;

   private STSConfiguration configuration;

   /*
    * (non-Javadoc)
    * @see org.picketlink.identity.federation.core.wstrust.WSTrustRequestHandler#initialize(
    *   org.picketlink.identity.federation.core.wstrust.STSConfiguration)
    */
   public void initialize(STSConfiguration configuration)
   {
      this.configuration = configuration;
   }

   /*
    * (non-Javadoc)
    * @see org.picketlink.identity.federation.core.wstrust.WSTrustRequestHandler#issue(
    *   org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken, java.security.Principal)
    */
   public RequestSecurityTokenResponse issue(RequestSecurityToken request, Principal callerPrincipal)
         throws WSTrustException
   {
      if (trace)
         log.trace("Issuing token for principal " + callerPrincipal);

      Document rstDocument = request.getRSTDocument();
      if (rstDocument == null)
         throw new IllegalArgumentException("Request does not contain the DOM Document");

      SecurityTokenProvider provider = null;

      // first try to obtain the security token provider using the applies-to contents.
      AppliesTo appliesTo = request.getAppliesTo();
      PublicKey providerPublicKey = null;
      if (appliesTo != null)
      {
         String serviceName = WSTrustUtil.parseAppliesTo(appliesTo);
         if (serviceName != null)
         {
            provider = this.configuration.getProviderForService(serviceName);
            if (provider != null)
            {
               request.setTokenType(URI.create(this.configuration.getTokenTypeForService(serviceName)));
               providerPublicKey = this.configuration.getServiceProviderPublicKey(serviceName);
            }
         }
      }
      // if applies-to is not available or if no provider was found for the service, use the token type.
      if (provider == null && request.getTokenType() != null)
      {
         provider = this.configuration.getProviderForTokenType(request.getTokenType().toString());
      }
      else if (appliesTo == null && request.getTokenType() == null)
         throw new WSTrustException("Either AppliesTo or TokenType must be present in a security token request");

      if (provider != null)
      {
         // create the request context and delegate token generation to the provider.
         WSTrustRequestContext requestContext = new WSTrustRequestContext(request, callerPrincipal);
         requestContext.setTokenIssuer(this.configuration.getSTSName());
         if (request.getLifetime() == null && this.configuration.getIssuedTokenTimeout() != 0)
         {
            // if no lifetime has been specified, use the configured timeout value.
            if (log.isDebugEnabled())
               log.debug("Lifetime has not been specified. Using the default timeout value.");
            request.setLifetime(WSTrustUtil.createDefaultLifetime(this.configuration.getIssuedTokenTimeout()));
         }
         requestContext.setServiceProviderPublicKey(providerPublicKey);

         // process the claims if needed.
         if (request.getClaims() != null)
         {
            ClaimsType claims = request.getClaims();
            ClaimsProcessor processor = this.configuration.getClaimsProcessor(claims.getDialect());
            // if there is a processor, process the claims and set the resulting attributes in the context.
            if (processor != null)
               requestContext.setClaimedAttributes(processor.processClaims(claims, callerPrincipal));
            else if (log.isDebugEnabled())
               log.debug("Claims have been specified in the request but no processor was found for dialect "
                     + claims.getDialect());
         }

         // get the key type and size from the request, setting default values if not specified.
         URI keyType = request.getKeyType();
         if (keyType == null)
         {
            if (log.isDebugEnabled())
               log.debug("No key type could be found in the request. Using the default BEARER type.");
            keyType = URI.create(WSTrustConstants.KEY_TYPE_BEARER);
            request.setKeyType(keyType);
         }
         long keySize = request.getKeySize();
         if (keySize == 0)
         {
            if (log.isDebugEnabled())
               log.debug("No key size could be found in the request. Using the default size. (" + KEY_SIZE + ")");
            keySize = KEY_SIZE;
            request.setKeySize(keySize);
         }

         // get the key wrap algorithm.
         URI keyWrapAlgo = request.getKeyWrapAlgorithm();

         // create proof-of-possession token and server entropy (if needed).
         RequestedProofTokenType requestedProofToken = null;
         EntropyType serverEntropy = null;

         if (WSTrustConstants.KEY_TYPE_SYMMETRIC.equalsIgnoreCase(keyType.toString()))
         {
            // symmetric key case: if client entropy is found, compute a key. If not, generate a new key.
            requestedProofToken = new RequestedProofTokenType();
            ObjectFactory objFactory = new ObjectFactory();

            byte[] clientSecret = null;
            EntropyType clientEntropy = request.getEntropy();
            if (clientEntropy != null)
               clientSecret = WSTrustUtil.getBinarySecret(clientEntropy);

            byte[] serverSecret = WSTrustUtil.createRandomSecret((int) keySize / 8);
            BinarySecretType serverBinarySecret = new BinarySecretType();
            serverBinarySecret.setType(WSTrustConstants.BS_TYPE_NONCE);
            serverBinarySecret.setValue(serverSecret);
            serverEntropy = new EntropyType();
            serverEntropy.getAny().add(objFactory.createBinarySecret(serverBinarySecret));

            if (clientSecret != null && clientSecret.length != 0)
            {
               // client secret has been specified - combine it with the sts secret.
               requestedProofToken.setAny(objFactory.createComputedKey(WSTrustConstants.CK_PSHA1));
               byte[] combinedSecret = null;
               try
               {
                  combinedSecret = WSTrustUtil.P_SHA1(clientSecret, serverSecret, (int) keySize / 8);
               }
               catch (Exception e)
               {
                  throw new WSTrustException("Error generating combined secret key", e);
               }
               requestContext.setProofTokenInfo(WSTrustUtil.createKeyInfo(combinedSecret, providerPublicKey,
                     keyWrapAlgo));
            }
            else
            {
               // client secret has not been specified - use the sts secret only.
               requestedProofToken.setAny(objFactory.createBinarySecret(serverBinarySecret));
               requestContext
                     .setProofTokenInfo(WSTrustUtil.createKeyInfo(serverSecret, providerPublicKey, keyWrapAlgo));
            }
         }
         else if (WSTrustConstants.KEY_TYPE_PUBLIC.equalsIgnoreCase(keyType.toString()))
         {
            // try to locate the client cert in the keystore using the caller principal as the alias.
            Certificate certificate = this.configuration.getCertificate(callerPrincipal.getName());
            if (certificate != null)
               requestContext.setProofTokenInfo(WSTrustUtil.createKeyInfo(certificate));
            // if no certificate was found in the keystore, check the UseKey contents.
            else if (request.getUseKey() != null)
            {
               UseKeyType useKeyType = request.getUseKey();
               Object value = useKeyType.getAny();
               if (value instanceof JAXBElement<?> || value instanceof Element)
               {
                  //TODO: parse the token properly. If it is a X509 cert, we should create a X509DataType with it.
                  KeyInfoType keyInfo = new KeyInfoType();
                  keyInfo.getContent().add(value);
                  requestContext.setProofTokenInfo(keyInfo);
               }
            }
            else
               throw new WSTrustException("Unable to locate client public key");
         }

         // issue the security token using the constructed context.
         provider.issueToken(requestContext);

         if (requestContext.getSecurityToken() == null)
            throw new WSTrustException("Token issued by provider " + provider.getClass().getName() + " is null");

         // construct the ws-trust security token response.
         RequestedSecurityTokenType requestedSecurityToken = new RequestedSecurityTokenType();
         requestedSecurityToken.setAny(requestContext.getSecurityToken().getTokenValue());

         RequestSecurityTokenResponse response = new RequestSecurityTokenResponse();
         if (request.getContext() != null)
            response.setContext(request.getContext());

         response.setTokenType(request.getTokenType());
         response.setLifetime(request.getLifetime());
         response.setAppliesTo(appliesTo);
         response.setKeySize(keySize);
         response.setKeyType(keyType);
         response.setRequestedSecurityToken(requestedSecurityToken);

         if (requestedProofToken != null)
            response.setRequestedProofToken(requestedProofToken);
         if (serverEntropy != null)
            response.setEntropy(serverEntropy);

         // set the attached and unattached references.
         if (requestContext.getAttachedReference() != null)
            response.setRequestedAttachedReference(requestContext.getAttachedReference());
         if (requestContext.getUnattachedReference() != null)
            response.setRequestedUnattachedReference(requestContext.getUnattachedReference());

         return response;
      }
      else
         throw new WSTrustException("Unable to find a token provider for the token request");
   }

   /*
    * (non-Javadoc)
    * @see org.picketlink.identity.federation.core.wstrust.WSTrustRequestHandler#renew(
    *   org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken, java.security.Principal)
    */
   public RequestSecurityTokenResponse renew(RequestSecurityToken request, Principal callerPrincipal)
         throws WSTrustException
   {
      // first validate the provided token signature to make sure it has been issued by this STS and hasn't been tempered.
      if (trace)
         log.trace("Validating token for renew request " + request.getContext());
      if (request.getRenewTargetElement() == null)
         throw new WSTrustException("Unable to renew token: renew target is null");

      Node securityToken = request.getRenewTargetElement().getFirstChild();
      if (this.configuration.signIssuedToken() && this.configuration.getSTSKeyPair() != null)
      {
         KeyPair keyPair = this.configuration.getSTSKeyPair();
         try
         {
            Document tokenDocument = DocumentUtil.createDocument();
            Node importedNode = tokenDocument.importNode(securityToken, true);
            tokenDocument.appendChild(importedNode);
            if (!XMLSignatureUtil.validate(tokenDocument, keyPair.getPublic()))
               throw new WSTrustException("Validation failure during renewal: digital signature is invalid");
         }
         catch (Exception e)
         {
            throw new WSTrustException("Validation failure during renewal: unable to verify digital signature", e);
         }
      }
      else
      {
         if (trace)
            log.trace("Security Token digital signature has NOT been verified. Either the STS has been configured"
                  + "not to sign tokens or the STS key pair has not been properly specified.");
      }

      // set default values where needed.
      if (request.getLifetime() == null && this.configuration.getIssuedTokenTimeout() != 0)
      {
         // if no lifetime has been specified, use the configured timeout value.
         if (log.isDebugEnabled())
            log.debug("Lifetime has not been specified. Using the default timeout value.");
         request.setLifetime(WSTrustUtil.createDefaultLifetime(this.configuration.getIssuedTokenTimeout()));
      }

      // create a context and dispatch to the proper security token provider for renewal.
      WSTrustRequestContext context = new WSTrustRequestContext(request, callerPrincipal);
      SecurityTokenProvider provider = this.configuration.getProviderForTokenElementNS(securityToken.getLocalName(),
            securityToken.getNamespaceURI());
      if (provider == null)
         throw new WSTrustException("No SecurityTokenProvider configured for " + securityToken.getNamespaceURI() + ":"
               + securityToken.getLocalName());
      provider.renewToken(context);

      // create the WS-Trust response with the renewed token.
      RequestedSecurityTokenType requestedSecurityToken = new RequestedSecurityTokenType();
      requestedSecurityToken.setAny(context.getSecurityToken().getTokenValue());

      RequestSecurityTokenResponse response = new RequestSecurityTokenResponse();
      if (request.getContext() != null)
         response.setContext(request.getContext());
      response.setTokenType(request.getTokenType());
      response.setLifetime(request.getLifetime());
      response.setRequestedSecurityToken(requestedSecurityToken);
      if (context.getAttachedReference() != null)
         response.setRequestedAttachedReference(context.getAttachedReference());
      if (context.getUnattachedReference() != null)
         response.setRequestedUnattachedReference(context.getUnattachedReference());
      return response;
   }

   /*
    * (non-Javadoc)
    * @see org.picketlink.identity.federation.core.wstrust.WSTrustRequestHandler#validate(
    *   org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken, java.security.Principal)
    */
   public RequestSecurityTokenResponse validate(RequestSecurityToken request, Principal callerPrincipal)
         throws WSTrustException
   {
      if (trace)
         log.trace("Started validation for request " + request.getContext());
      Document rstDocument = request.getRSTDocument();
      if (rstDocument == null)
         throw new IllegalArgumentException("Request does not contain the DOM Document");

      if (request.getValidateTargetElement() == null)
         throw new WSTrustException("Unable to validate token: validate target is null");

      if (request.getTokenType() == null)
         request.setTokenType(URI.create(WSTrustConstants.STATUS_TYPE));

      Node securityToken = request.getValidateTargetElement().getFirstChild();
      SecurityTokenProvider provider = this.configuration.getProviderForTokenElementNS(securityToken.getLocalName(),
            securityToken.getNamespaceURI());
      if (provider == null)
         throw new WSTrustException("No SecurityTokenProvider configured for " + securityToken.getNamespaceURI() + ":"
               + securityToken.getLocalName());

      WSTrustRequestContext context = new WSTrustRequestContext(request, callerPrincipal);
      StatusType status = null;

      // validate the security token digital signature.
      if (this.configuration.signIssuedToken() && this.configuration.getSTSKeyPair() != null)
      {
         KeyPair keyPair = this.configuration.getSTSKeyPair();
         try
         {
            if (trace)
            {
               try
               {
                  log.trace("Going to validate:" + DocumentUtil.getNodeAsString(securityToken));
               }
               catch (Exception e)
               {
               }
            }
            Document tokenDocument = DocumentUtil.createDocument();
            Node importedNode = tokenDocument.importNode(securityToken, true);
            tokenDocument.appendChild(importedNode);
            if (!XMLSignatureUtil.validate(tokenDocument, keyPair.getPublic()))
            {
               status = new StatusType();
               status.setCode(WSTrustConstants.STATUS_CODE_INVALID);
               status.setReason("Validation failure: digital signature is invalid");
            }
         }
         catch (Exception e)
         {
            status = new StatusType();
            status.setCode(WSTrustConstants.STATUS_CODE_INVALID);
            status.setReason("Validation failure: unable to verify digital signature: " + e.getMessage());
         }
      }
      else
      {
         if (trace)
            log.trace("Security Token digital signature has NOT been verified. Either the STS has been configured"
                  + "not to sign tokens or the STS key pair has not been properly specified.");
      }

      // if the signature is valid, then let the provider perform any additional validation checks.
      if (status == null)
      {
         if (trace)
            log.trace("Delegating token validation to token provider");
         provider.validateToken(context);
         status = context.getStatus();
      }

      // construct and return the response.
      RequestSecurityTokenResponse response = new RequestSecurityTokenResponse();
      if (request.getContext() != null)
         response.setContext(request.getContext());
      response.setTokenType(request.getTokenType());
      response.setStatus(status);

      return response;
   }

   /*
    * (non-Javadoc)
    * @see org.picketlink.identity.federation.core.wstrust.WSTrustRequestHandler#cancel(
    *   org.picketlink.identity.federation.core.wstrust.wrappers.RequestSecurityToken, java.security.Principal)
    */
   public RequestSecurityTokenResponse cancel(RequestSecurityToken request, Principal callerPrincipal)
         throws WSTrustException
   {
      // check if request contains all required elements.
      Document rstDocument = request.getRSTDocument();
      if (rstDocument == null)
         throw new IllegalArgumentException("Request does not contain the DOM Document");
      if (request.getCancelTargetElement() == null)
         throw new WSTrustException("Illegal cancel request: cancel target is null");

      // obtain the token provider that will handle the request.
      Node securityToken = request.getCancelTargetElement().getFirstChild();
      SecurityTokenProvider provider = this.configuration.getProviderForTokenElementNS(securityToken.getLocalName(),
            securityToken.getNamespaceURI());
      if (provider == null)
         throw new WSTrustException("No SecurityTokenProvider configured for " + securityToken.getNamespaceURI() + ":"
               + securityToken.getLocalName());

      // create a request context and dispatch to the provider.
      WSTrustRequestContext context = new WSTrustRequestContext(request, callerPrincipal);
      provider.cancelToken(context);
      
      // if no exception has been raised, the token has been successfully canceled.
      RequestSecurityTokenResponse response = new RequestSecurityTokenResponse();
      if (request.getContext() != null)
         response.setContext(request.getContext());
      response.setRequestedTokenCancelled(new RequestedTokenCancelledType());
      return response;
   }

   public Document postProcess(Document rstrDocument, RequestSecurityToken request) throws WSTrustException
   {
      if (WSTrustConstants.ISSUE_REQUEST.equals(request.getRequestType().toString())
            || WSTrustConstants.RENEW_REQUEST.equals(request.getRequestType().toString()))
      {
         rstrDocument = DocumentUtil.normalizeNamespaces(rstrDocument);

         //Sign the security token
         if (this.configuration.signIssuedToken() && this.configuration.getSTSKeyPair() != null)
         {
            KeyPair keyPair = this.configuration.getSTSKeyPair();
            URI signatureURI = request.getSignatureAlgorithm();
            String signatureMethod = signatureURI != null ? signatureURI.toString() : SignatureMethod.RSA_SHA1;
            try
            {
               Node rst = rstrDocument
                     .getElementsByTagNameNS(WSTrustConstants.BASE_NAMESPACE, "RequestedSecurityToken").item(0);
               Element tokenElement = (Element) rst.getFirstChild();
               if (trace)
                  log.trace("NamespaceURI of element to be signed:" + tokenElement.getNamespaceURI());

               rstrDocument = XMLSignatureUtil.sign(rstrDocument, tokenElement, keyPair, DigestMethod.SHA1,
                     signatureMethod, "#" + tokenElement.getAttribute("ID"));
               if (trace)
               {
                  try
                  {
                     log.trace("Signed Token:" + DocumentUtil.getNodeAsString(tokenElement));

                     Document tokenDocument = DocumentUtil.createDocument();
                     tokenDocument.appendChild(tokenDocument.importNode(tokenElement, true));
                     log.trace("valid=" + XMLSignatureUtil.validate(tokenDocument, keyPair.getPublic()));

                  }
                  catch (Exception ignore)
                  {
                  }
               }
            }
            catch (Exception e)
            {
               throw new WSTrustException("Failed to sign security token", e);
            }
         }

         // encrypt the security token if needed.
         if (this.configuration.encryptIssuedToken())
         {
            // get the public key that will be used to encrypt the token.
            PublicKey providerPublicKey = null;
            if (request.getAppliesTo() != null)
            {
               String serviceName = WSTrustUtil.parseAppliesTo(request.getAppliesTo());
               if (trace)
                  log.trace("Locating public key for service provider " + serviceName);
               if (serviceName != null)
                  providerPublicKey = this.configuration.getServiceProviderPublicKey(serviceName);
            }
            if (providerPublicKey == null)
            {
               log.warn("Security token should be encrypted but no encrypting key could be found");
            }
            else
            {
               // generate the secret key.
               long keySize = request.getKeySize();
               byte[] secret = WSTrustUtil.createRandomSecret((int) keySize / 8);
               SecretKey secretKey = new SecretKeySpec(secret, "AES");

               // encrypt the security token.
               Node rst = rstrDocument
                     .getElementsByTagNameNS(WSTrustConstants.BASE_NAMESPACE, "RequestedSecurityToken").item(0);
               Element tokenElement = (Element) rst.getFirstChild();
               try
               {
                  XMLEncryptionUtil.encryptElement(rstrDocument, tokenElement, providerPublicKey, secretKey,
                        (int) keySize);
               }
               catch (ProcessingException e)
               {
                  throw new WSTrustException("Unable to encrypt security token", e);
               }
            }
         }
      }

      return rstrDocument;
   }

}