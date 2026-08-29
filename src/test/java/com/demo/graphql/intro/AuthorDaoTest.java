package com.demo.graphql.intro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorDaoTest {

    private AuthorDao authorDao;
    private List<Author> authors;

    @BeforeEach
    void setUp() {
        authors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Author a = new Author();
            a.setId("Author" + i);
            a.setName("Author " + i);
            authors.add(a);
        }
        authorDao = new AuthorDao(authors);
    }

    @Test
    void getAuthor_returnsCorrectAuthor() {
        Author author = authorDao.getAuthor("Author1");
        assertThat(author).isNotNull();
        assertThat(author.getName()).isEqualTo("Author 1");
    }

    @Test
    void getAuthor_whenNotFound_throws() {
        assertThrows(RuntimeException.class, () -> authorDao.getAuthor("no-such"));
    }
}
