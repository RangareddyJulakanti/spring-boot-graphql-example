package com.demo.graphql.intro;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;


@GraphQlTest(PostController.class)
@Import(GraphqlConfiguration.class)
class PostControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    void givenPosts_whenExecuteQueryForRecentPosts_thenReturnResponse() {
        String documentName = "recent_posts";

        graphQlTester.documentName(documentName)
          .variable("count", 2)
          .variable("offset", 0)
          .execute()
          .path("$")
          .matchesJson(PostControllerGraphQlTest.expected(documentName));
    }

    @Test
    void givenNewPostData_whenExecuteMutation_thenNewPostCreated() {
        String documentName = "create_post";

        graphQlTester.documentName(documentName)
          .variable("title", "New Post")
          .variable("text", "New post text")
          .variable("category", "category")
          .variable("authorId", "Author0")
          .execute()
          .path("createPost.id").hasValue()
          .path("createPost.title").entity(String.class).isEqualTo("New Post")
          .path("createPost.text").entity(String.class).isEqualTo("New post text")
          .path("createPost.category").entity(String.class).isEqualTo("category");
    }

    private static String expected(String fileName) {
        java.nio.file.Path path = java.nio.file.Paths.get("src/test/resources/graphql-test/" + fileName + "_expected_response.json");
        try {
            return new String(java.nio.file.Files.readAllBytes(path));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
