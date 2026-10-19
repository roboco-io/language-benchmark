# Update comment: PUT /api/articles/:slug/comments/:id

# Update comment without auth
PUT {{host}}/api/articles/some-slug/comments/1
{
  "comment": {
    "body": "test"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: register the comment author
POST {{host}}/api/users
{
  "user": {
    "username": "cu_author_{{uid}}",
    "email": "cu_author_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
author_token: jsonpath "$.user.token"

# Setup: register another user
POST {{host}}/api/users
{
  "user": {
    "username": "cu_other_{{uid}}",
    "email": "cu_other_{{uid}}@test.com",
    "password": "password123"
  }
}
HTTP 201
[Captures]
other_token: jsonpath "$.user.token"

# Setup: create an article
POST {{host}}/api/articles
Authorization: Token {{author_token}}
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

# Setup: create a comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "Original body"
  }
}
HTTP 201
[Captures]
comment_id: jsonpath "$.comment.id"
created_at: jsonpath "$.comment.createdAt"
updated_at: jsonpath "$.comment.updatedAt"

# Update comment as its author
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "Updated body"
  }
}
HTTP 200
[Asserts]
jsonpath "$.comment.id" == {{comment_id}}
jsonpath "$.comment.body" == "Updated body"
jsonpath "$.comment.createdAt" == "{{created_at}}"
jsonpath "$.comment.updatedAt" isIsoDate
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.author.username" == "cu_author_{{uid}}"
jsonpath "$.comment.author.bio" == null
jsonpath "$.comment.author.image" == null
jsonpath "$.comment.author.following" == false

# List comments returns the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "Updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update comment as another user
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{other_token}}
{
  "comment": {
    "body": "Hijacked"
  }
}
HTTP 403
[Asserts]
jsonpath "$.errors.comment[0]" == "forbidden"

# Update comment with empty body
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update non-existent comment
PUT {{host}}/api/articles/{{slug}}/comments/99999999
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "test"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.comment[0]" == "not found"

# Update comment on non-existent article
PUT {{host}}/api/articles/no-such-article-{{uid}}/comments/{{comment_id}}
Authorization: Token {{author_token}}
{
  "comment": {
    "body": "test"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Failed updates left the body unchanged
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "Updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}
Authorization: Token {{author_token}}
HTTP 204
