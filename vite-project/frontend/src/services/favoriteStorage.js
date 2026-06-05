const FAVORITE_KEY = "a-eye:favorites";
const CATEGORY_KEY = "a-eye:favorite-categories";

const DEFAULT_CATEGORIES = ["집", "회사", "병원", "복지관", "대중교통", "기타"];

function readJson(key, fallback) {
  try {
    const stored = localStorage.getItem(key);
    return stored ? JSON.parse(stored) : fallback;
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function createId() {
  if (crypto.randomUUID) {
    return crypto.randomUUID();
  }

  return `favorite_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

export function getFavoriteCategories() {
  return readJson(CATEGORY_KEY, DEFAULT_CATEGORIES);
}

export function addFavoriteCategory(name) {
  const nextName = name.trim();

  if (!nextName) {
    return getFavoriteCategories();
  }

  const categories = getFavoriteCategories();

  if (categories.includes(nextName)) {
    return categories;
  }

  const nextCategories = [...categories, nextName];
  writeJson(CATEGORY_KEY, nextCategories);
  return nextCategories;
}

export function removeFavoriteCategory(name) {
  const categories = getFavoriteCategories().filter((category) => category !== name);
  writeJson(CATEGORY_KEY, categories);
  return categories;
}

export function getFavorites() {
  return readJson(FAVORITE_KEY, []);
}

export function addFavorite(place, category) {
  const favorites = getFavorites();
  const nextFavorite = {
    id: createId(),
    placeId: place.id,
    name: place.name,
    type: place.type || "custom",
    lat: place.lat,
    lng: place.lng,
    address: place.address || "",
    category,
    createdAt: new Date().toISOString(),
  };

  const nextFavorites = [nextFavorite, ...favorites];
  writeJson(FAVORITE_KEY, nextFavorites);
  return nextFavorites;
}

export function deleteFavoriteById(id) {
  const nextFavorites = getFavorites().filter((favorite) => favorite.id !== id);
  writeJson(FAVORITE_KEY, nextFavorites);
  return nextFavorites;
}
