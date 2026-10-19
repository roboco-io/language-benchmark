# Tests for PUT /api/articles/:slug/comments/:id (update a comment).

# Update comment without auth
PUT {{host}}/api/articles/some-slug/comments/1
{
  "comment": {
    "body": "x"
  }
}
HTTP 401
[Asserts]
jsonpath "$.errors.token[0]" == "is missing"

# Setup: Register the comment author
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

# Setup: Register another user
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
token2: jsonpath "$.user.token"

# Setup: Create article
POST {{host}}/api/articles
Authorization: Token {{token}}
{
  "article": {
    "title": "Comment Update Article {{uid}}",
    "description": "For comment updates",
    "body": "Article body"
  }
}
HTTP 201
[Captures]
slug: jsonpath "$.article.slug"

# Setup: Create comment
POST {{host}}/api/articles/{{slug}}/comments
Authorization: Token {{token}}
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

# Update comment as author (delayed so updatedAt can differ)
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
[Options]
delay: 50
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
jsonpath "$.comment.updatedAt" matches "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
jsonpath "$.comment.updatedAt" != "{{updated_at}}"
jsonpath "$.comment.author.username" == "cu_{{uid}}"
jsonpath "$.comment.author.following" == false

# List comments reflects the new body
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments" count == 1
jsonpath "$.comments[0].id" == {{comment_id}}
jsonpath "$.comments[0].body" == "Updated body"
jsonpath "$.comments[0].createdAt" == "{{created_at}}"

# Update comment as another user
PUT {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token2}}
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
Authorization: Token {{token}}
{
  "comment": {
    "body": ""
  }
}
HTTP 422
[Asserts]
jsonpath "$.errors.body[0]" == "can't be blank"

# Update unknown comment id
PUT {{host}}/api/articles/{{slug}}/comments/999999999
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
PUT {{host}}/api/articles/no-such-article-{{uid}}/comments/{{comment_id}}
Authorization: Token {{token}}
{
  "comment": {
    "body": "x"
  }
}
HTTP 404
[Asserts]
jsonpath "$.errors.article[0]" == "not found"

# Body is unchanged after rejected updates
GET {{host}}/api/articles/{{slug}}/comments
HTTP 200
[Asserts]
jsonpath "$.comments[0].body" == "Updated body"

# Cleanup
DELETE {{host}}/api/articles/{{slug}}/comments/{{comment_id}}
Authorization: Token {{token}}
HTTP 204
