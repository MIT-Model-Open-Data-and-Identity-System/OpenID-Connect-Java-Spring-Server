package org.mitre.openid.connect;

import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minidev.json.JSONObject;

import org.mitre.jwt.signer.service.JwtSigningAndValidationService;
import org.mitre.jwt.signer.service.impl.JWKSetSigningAndValidationServiceCacheService;
import org.mitre.oauth2.exception.NonceReuseException;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.openid.connect.model.Nonce;
import org.mitre.openid.connect.service.NonceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.common.exceptions.InvalidScopeException;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.AuthorizationRequestManager;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.OAuthRequest;
import org.springframework.stereotype.Component;

import com.google.common.base.Strings;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;

@Component("authorizationRequestManager")
public class ConnectAuthorizationRequestManager implements AuthorizationRequestManager {

	private static Logger logger = LoggerFactory.getLogger(ConnectAuthorizationRequestManager.class);
	
	@Autowired
	private NonceService nonceService;
	
	@Autowired
	private ClientDetailsEntityService clientDetailsService;

	@Autowired
	private JWKSetSigningAndValidationServiceCacheService validators;

	/**
	 * Constructor with arguments
	 * 
	 * @param clientDetailsService
	 * @param nonceService
	 */
	public ConnectAuthorizationRequestManager(ClientDetailsEntityService clientDetailsService, NonceService nonceService) {
		this.clientDetailsService = clientDetailsService;
		this.nonceService = nonceService;
	}
	
	/**
	 * Default empty constructor
	 */
	public ConnectAuthorizationRequestManager() {
		
	}

	@Override
	public AuthorizationRequest createAuthorizationRequest(Map<String, String> inputParams) {

		Map<String, String> parameters = processRequestObject(inputParams);
		
		String clientId = parameters.get("client_id");
		ClientDetails client = null;
		
		if (clientId != null) {
			client = clientDetailsService.loadClientByClientId(clientId);
		}
		
		String requestNonce = parameters.get("nonce");
		
		AuthorizationRequest request = new AuthorizationRequest(parameters, Collections.<String, String> emptyMap(), 
				parameters.get(OAuthRequest.CLIENT_ID), 
				OAuth2Utils.parseParameterList(parameters.get(OAuthRequest.SCOPE)), null,
				null, false, parameters.get(OAuthRequest.STATE), 
				parameters.get(OAuthRequest.REDIRECT_URI), 
				OAuth2Utils.parseParameterList(parameters.get(OAuthRequest.RESPONSE_TYPE)));
		
		//Only process if the user is authenticated. If the user is not authenticated yet, this 
		//code will be called a second time once the user is redirected from the login page back 
		//to the auth endpoint.
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		
		if (requestNonce != null && principal != null && principal instanceof User) {

			if (!nonceService.alreadyUsed(clientId, requestNonce)) {
				Nonce nonce = nonceService.create(clientId, requestNonce);
				nonceService.save(nonce);
			}
			else {
				throw new NonceReuseException(client == null ? "unidentified client" : client.getClientId(), requestNonce);
			}
	
		}
		
		Set<String> scopes = OAuth2Utils.parseParameterList(parameters.get("scope"));
		if ((scopes == null || scopes.isEmpty()) && client != null) {
			//TODO: do we want to allow default scoping at all?
			Set<String> clientScopes = client.getScope();
			scopes = clientScopes;
		}
		
		request.setScope(scopes);
		
		return request;
	}

	/**
	 * @param inputParams
	 * @return
	 */
    private Map<String, String> processRequestObject(Map<String, String> inputParams) {
    	
    	String jwtString = inputParams.get("request");
    	
    	// if there's no request object, bail early
    	if (Strings.isNullOrEmpty(jwtString)) {
    		return inputParams;
    	}

    	// start by copying over what's already in there
    	Map<String, String> parameters = new HashMap<String, String>(inputParams);

    	// parse the request object
        try {
        	SignedJWT jwsObject = SignedJWT.parse(jwtString);
			JSONObject claims = jwsObject.getPayload().toJSONObject(); 
			
			// TODO: check parameter consistency, move keys to constants
			
			String clientId = JSONObjectUtils.getString(claims, "client_id");
			if (clientId != null) {
				parameters.put("client_id", clientId);
			}
			
			ClientDetailsEntity client = clientDetailsService.loadClientByClientId(clientId);

			if (client.getJwksUri() == null) {
				throw new InvalidClientException("Client must have a JWK URI registered to use request objects.");
			}
			
			// check JWT signature
			JwtSigningAndValidationService validator = validators.get(client.getJwksUri());
			if (validator == null) {
				throw new InvalidClientException("Client must have a JWK URI registered to use request objects.");
			}
			
			if (!validator.validateSignature(jwsObject)) {
				throw new AuthenticationServiceException("Signature did not validate for presented JWT request object.");
			}
			
			/*
			 * if (in Claims):
			 * 		if (in params):
			 * 			if (equal):
			 * 				all set
			 * 			else (not equal):
			 * 				error
			 * 		else (not in params):
			 * 			add to params
			 * else (not in claims):
			 * 		we don't care
			 */
			
			String responseTypes = JSONObjectUtils.getString(claims, "response_type");
			if (responseTypes != null) {
				parameters.put("response_type", responseTypes);
			}
			
			if (claims.get("redirect_uri") != null) {
				if (inputParams.containsKey("redirect_uri") == false) {
					parameters.put("redirect_uri", JSONObjectUtils.getString(claims, "redirect_uri"));
				}
			}
			
			String state = JSONObjectUtils.getString(claims, "state");
			if(state != null) {
				if (inputParams.containsKey("state") == false) {
					parameters.put("state", state);
				}
			}
			
			String nonce = JSONObjectUtils.getString(claims, "nonce");
			if(nonce != null) {
				if (inputParams.containsKey("nonce") == false) {
					parameters.put("nonce", nonce);
				}
			}
			
			String display = JSONObjectUtils.getString(claims, "display");
			if (display != null) {
				if (inputParams.containsKey("display") == false) {
					parameters.put("display", display);
				}
			}
			
			String prompt = JSONObjectUtils.getString(claims, "prompt");
			if (prompt != null) {
				if (inputParams.containsKey("prompt") == false) {
					parameters.put("prompt", prompt);
				}
			}
			
			String scope = JSONObjectUtils.getString(claims, "scope");
			if (scope != null) {
				if (inputParams.containsKey("scope") == false) {
					parameters.put("scope", scope);
				}
			}
		
        } catch (ParseException e) {
        	logger.error("ParseException while parsing RequestObject:", e);
        }
		return parameters;
    }

	@Override
	public void validateParameters(Map<String, String> parameters, ClientDetails clientDetails) {
		if (parameters.containsKey("scope")) {
			if (clientDetails.isScoped()) {
				Set<String> validScope = clientDetails.getScope();
				for (String scope : OAuth2Utils.parseParameterList(parameters.get("scope"))) {
					if (!validScope.contains(scope)) {
						throw new InvalidScopeException("Invalid scope: " + scope, validScope);
					}
				}
			}
		}
	}

}
