package com.demo.graphql.intro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostDaoTest {

    private PostDao postDao;
    private List<Post> posts;

    @BeforeEach
    void setUp() {
        posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Post p = new Post();
            p.setId("id" + i);
            p.setTitle("title" + i);
            p.setAuthorId(i % 2 == 0 ? "AuthorA" : "AuthorB");
            posts.add(p);
        }
        postDao = new PostDao(posts);
    }

    @Test
    void getRecentPosts_returnsWindowedList() {
        List<Post> recent = postDao.getRecentPosts(2, 1);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getId()).isEqualTo("id1");
        assertThat(recent.get(1).getId()).isEqualTo("id2");
    }

    @Test
    void getAuthorPosts_filtersByAuthor() {
        List<Post> authorPosts = postDao.getAuthorPosts("AuthorA");
        assertThat(authorPosts).allMatch(p -> "AuthorA".equals(p.getAuthorId()));
    }

    @Test
    void savePost_appendsToUnderlyingList() {
        Post newPost = new Post();
        newPost.setId("new1");
        postDao.savePost(newPost);

        assertThat(posts).contains(newPost);
    }
}
