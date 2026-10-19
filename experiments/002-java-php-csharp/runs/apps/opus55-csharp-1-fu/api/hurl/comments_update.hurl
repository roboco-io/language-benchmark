# Tests for PUT /api/articles/:slug/comments/:id (not part of the upstream suite).

# Update comment without a token
PUT {{host}}/api/articles/some-slug/comments/1
{
  "comment": {
    "body": "x"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: comment author
POST {{host}}/api/users
{
  "user": {
    "username": "cu_{{uid}}",
    "email": "cu_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
token: jsonpath "$.user.token"

# Setup: another user
POST {{host}}/api/users
{
  "user": {
    "username": "cu2_{{uid}}",
    "email": "cu2_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
other_token: jsonpath "$.user.token"

# Setup: article
POST {{host}}/api/articles
Authorization: Token {{token}}
{
  "article": {
    "title": "Comment Update Art {{uid}}",
    "description": "test",
    "body": "test"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
{
  "comment": {
    "body": "original body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"
created_at: jsonpath "$.comment.createdAt"
updated_at: jsonpath "$.comment.updatedAt"

# Update comment as author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "updated body"
  }
}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "updated body"
jsonpath "$.comment.createdAt" == "{{created_at}}"
jsonpath "$.comment.updatedAt" isIsoDate
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.author.username" == "cu_{{uid}}"
jsonpath "$.comment.author.following" == false

# List comments shows the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update comment as another user
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{other_token}}
{
  "comment": {
    "body": "hijacked"
  }
}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Update comment with empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update unknown comment
PUT {{host}}/api/articles/{{slug}}/comments/99999999
Authorization: Token {{token}}
{
  "comment": {
    "body": "x"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update comment on unknown article
PUT {{host}}/api/articles/unknown-slug-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "x"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Body unchanged after failed updates
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{token}}
HTTP 204
