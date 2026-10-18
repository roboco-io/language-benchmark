# Setup: register author, other user, article, comment
POST {{host}}/api/users
{"user": {"username": "cu_a_{{uid}}", "email": "cu_a_{{uid}}@test.com", "password": "password123"}}
HTTP 201
[Captures]
token: jsonpath "$.user.token"

POST {{host}}/api/users
{"user": {"username": "cu_b_{{uid}}", "email": "cu_b_{{uid}}@test.com", "password": "password123"}}
HTTP 201
[Captures]
token2: jsonpath "$.user.token"

POST {{host}}/api/articles
Authorization: Token {{token}}
{"article": {"title": "Comment Update {{uid}}", "description": "d", "body": "b"}}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
{"comment": {"body": "original"}}
HTTP 201
[Captures]
cid: jsonpath "$.comment.id"
created: jsonpath "$.comment.createdAt"

# Update succeeds for the author
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token}}
{"comment": {"body": "edited"}}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{cid}}
jsonpath "$.comment.body" == "edited"
jsonpath "$.comment.createdAt" == "{{created}}"
jsonpath "$.comment.updatedAt" exists
jsonpath "$.comment.author.username" == "cu_a_{{uid}}"

# The list returns the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "edited"
jsonpath "$.comments[0].id" == {{cid}}

# Another user is forbidden
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token2}}
{"comment": {"body": "hijack"}}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Missing token
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
{"comment": {"body": "x"}}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{cid}}
Authorization: Token {{token}}
{"comment": {"body": ""}}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Unknown comment id
PUT {{host}}/api/articles/{{slug}}/comments/999999999
Authorization: Token {{token}}
{"comment": {"body": "x"}}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Unknown article
PUT {{host}}/api/articles/no-such-slug-{{uid}}/comments/{{cid}}
Authorization: Token {{token}}
{"comment": {"body": "x"}}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Body unchanged after failed attempts
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "edited"
