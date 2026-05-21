package com.jvmobservability.demo.model;

/**
 * Request body for creating a song.
 * Keeps id out of the client payload — the service generates it.
 */
public record CreateSongRequest(String title, String artist) {}
