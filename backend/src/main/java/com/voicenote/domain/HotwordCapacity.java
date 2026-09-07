package com.voicenote.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "hotword_capacity")
public class HotwordCapacity {
    @Id private Integer id;
    @Version private long version;

    protected HotwordCapacity() { }
    public Integer getId() { return id; }
}
