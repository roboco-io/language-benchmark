# Tests for PUT /api/articles/:slug/comments/:id (custom, not part of the official suite)

# Update comment without auth
PUT {{host}}/api/articles/some-slug/comments/1
{ "comment": { "body": "x" } }
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: author
POST {{host}}/api/users
{ "user": { "username": "uc_a_{{uid}}", "email": "uc_a_{{uid}}@test.com", "password": "password123" } }
HTTP 201
[Captures]
token: jsonpath "$.user.token"

# Setup: other user
POST {{host}}/api/users
{ "user": { "username": "uc_b_{{uid}}", "email": "uc_b_{{uid}}@test.com", "password": "password123" } }
HTTP 201
[Captures]
token2: jsonpath "$.user.token"

POST {{host}}/api/articles
Authorization: Token {{token}}
{ "article": { "title": "Update Comment Art {{uid}}", "description": "d", "body": "b" } }
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
{ "comment": { "body": "original" } }
HTTP 201
[Captures]
cid: jsonpath "$.comment.id"
created: jsonpath "$.comment.createdAt"
updated: jsonpath "$.comment.updatedAt"

# Forbidden for non-author
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token2}}
{ "comment": { "body": "hijack" } }
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token}}
{ "comment": { "body": "" } }
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Unknown comment
PUT {{host}}/api/articles/{{slug}}/comments/999999999
Authorization: Token {{token}}
{ "comment": { "body": "x" } }
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Unknown article
PUT {{host}}/api/articles/no-such-slug-{{uid}}/comments/{{cid}}
Authorization: Token {{token}}
{ "comment": { "body": "x" } }
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Successful update (delay so updatedAt differs at second resolution)
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token}}
[Options]
delay: 1200
{ "comment": { "body": "edited" } }
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{cid}}
jsonpath "$.comment.body" == "edited"
jsonpath "$.comment.createdAt" == "{{created}}"
jsonpath "$.comment.updatedAt" != "{{updated}}"
jsonpath "$.comment.author.username" == "uc_a_{{uid}}"

# Listing shows new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].body" == "edited"
jsonpath "$.comments[0].id" == {{cid}}
