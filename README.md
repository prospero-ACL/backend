# Prospero RAG ACL Backen App

## Description

This is a java Spring project that acts as the backend for the Prospero RAG ACL web application. It is a REST API that is used to interact with the database and to retrieve information from the OpenAI API. In addition it uses Spring Security to authenticate users and to authorize them to use the API.

## Authentication and Authorization

Login is via Google or GitHub OAuth2 (Spring Security). On success the app issues a stateless JWT
in an httpOnly `access_token` cookie (1 hour expiry) — no server-side sessions, no bearer header.
Logout clears that cookie; there is no server-side token revocation, so a copied token stays valid
until it naturally expires.

## Document insertion
