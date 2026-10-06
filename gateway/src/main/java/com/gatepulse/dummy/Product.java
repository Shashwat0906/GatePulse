package com.gatepulse.dummy;

/** A tiny product catalogue entry served by the dummy backends. */
public record Product(int id, String name, String category, double price, int stock) {
}
