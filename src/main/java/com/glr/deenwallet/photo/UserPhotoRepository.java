package com.glr.deenwallet.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserPhotoRepository extends JpaRepository<UserPhoto, UUID> {

    /** (user id, updated at) of every photo, without the image bytes. */
    @Query("select p.userId, p.updatedAt from UserPhoto p")
    List<Object[]> versions();
}
