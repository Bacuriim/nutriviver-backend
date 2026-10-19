-- Dados iniciais. Idempotente: roda a cada start sem duplicar.
INSERT INTO goal (goal_name) VALUES
    ('Perder peso'), ('Manter peso'), ('Ganhar massa'), ('Ter mais disposição')
ON CONFLICT DO NOTHING;

INSERT INTO restriction (restriction_name) VALUES
    ('Glúten'), ('Lactose'), ('Oleaginosas'), ('Frutos do mar'), ('Soja'), ('Outro')
ON CONFLICT DO NOTHING;

INSERT INTO preference (preference_name) VALUES
    ('Frango'), ('Ovo'), ('Abacate'), ('Brócolis'), ('Tomate'), ('Batata-doce')
ON CONFLICT DO NOTHING;

INSERT INTO category (name) VALUES
    ('Proteína'), ('Vegetal'), ('Fruta'), ('Carboidrato'), ('Laticínio'), ('Tempero')
ON CONFLICT DO NOTHING;

INSERT INTO ingredient (name, category)
SELECT v.name, c.id_category
FROM (VALUES
    ('Ovo', 'Proteína'), ('Frango', 'Proteína'), ('Salmão', 'Proteína'), ('Feijão', 'Proteína'),
    ('Tomate', 'Vegetal'), ('Brócolis', 'Vegetal'), ('Cenoura', 'Vegetal'), ('Alface', 'Vegetal'),
    ('Espinafre', 'Vegetal'), ('Abacate', 'Fruta'), ('Banana', 'Fruta'), ('Morango', 'Fruta'),
    ('Arroz', 'Carboidrato'), ('Batata', 'Carboidrato'), ('Batata-doce', 'Carboidrato'),
    ('Tortilha integral', 'Carboidrato'), ('Aveia', 'Carboidrato'), ('Quinoa', 'Carboidrato'),
    ('Queijo', 'Laticínio'), ('Iogurte natural', 'Laticínio'),
    ('Azeite de oliva', 'Tempero'), ('Sal', 'Tempero'), ('Mel', 'Tempero')
) AS v(name, cat)
JOIN category c ON c.name = v.cat
ON CONFLICT DO NOTHING;

INSERT INTO recipe (name, description, prep_time, image_url, difficulty, meal_type, steps) VALUES
    ('Omelete de forno', 'Omelete assada com espinafre, tomate e queijo.', 15,
     'https://images.unsplash.com/photo-1510693206972-df098062cb71?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Café da manhã',
     'Preaqueça o forno a 200 °C.\nBata os ovos com sal.\nMisture espinafre, tomate e queijo.\nAsse por 12 minutos.'),
    ('Wrap de frango', 'Wrap integral com frango desfiado, alface e tomate.', 20,
     'https://images.unsplash.com/photo-1626700051175-6818013e1d4f?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Almoço',
     'Grelhe e desfie o frango.\nAqueça a tortilha.\nMonte com alface, tomate e frango.\nEnrole e sirva.'),
    ('Salmão com batata-doce', 'Salmão grelhado com batata-doce e brócolis no vapor.', 35,
     'https://images.unsplash.com/photo-1467003909585-2f8a72700288?auto=format&fit=crop&w=500&q=80',
     'Média', 'Jantar',
     'Cozinhe a batata-doce em cubos.\nCozinhe o brócolis no vapor.\nGrelhe o salmão com azeite e sal.\nSirva tudo junto.'),
    ('Panqueca de banana', 'Panqueca de aveia e banana, sem farinha de trigo.', 10,
     'https://images.unsplash.com/photo-1528207776546-365bb710ee93?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Café da manhã',
     'Amasse a banana.\nMisture com ovo e aveia.\nDoure dos dois lados em frigideira antiaderente.'),
    ('Bowl de quinoa', 'Quinoa com abacate, tomate e cenoura ralada.', 25,
     'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Almoço',
     'Cozinhe a quinoa.\nRale a cenoura e corte tomate e abacate.\nMonte o bowl e tempere com azeite e sal.'),
    ('Arroz, feijão e frango grelhado', 'Prato clássico brasileiro, equilibrado.', 50,
     'https://images.unsplash.com/photo-1604908176997-125f25cc6f3d?auto=format&fit=crop&w=500&q=80',
     'Média', 'Almoço',
     'Cozinhe o arroz.\nAqueça o feijão já cozido.\nGrelhe o frango temperado com sal.\nSirva com salada.'),
    ('Iogurte com morango e mel', 'Lanche rápido e leve.', 5,
     'https://images.unsplash.com/photo-1488477181946-6428a0291777?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Lanche',
     'Coloque o iogurte em uma tigela.\nAdicione morangos picados.\nFinalize com mel e aveia.'),
    ('Salada de frango com abacate', 'Salada fresca rica em proteína.', 15,
     'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?auto=format&fit=crop&w=500&q=80',
     'Fácil', 'Jantar',
     'Grelhe e corte o frango.\nMisture alface, tomate e abacate.\nTempere com azeite e sal.')
ON CONFLICT DO NOTHING;

INSERT INTO recipe_ingredient (id_recipe, id_ingredient, quantity, measure_unity)
SELECT r.id_recipe, i.id_ingredient, v.qty, v.unit
FROM (VALUES
    ('Omelete de forno', 'Ovo', 3, 'unidade'), ('Omelete de forno', 'Espinafre', 1, 'xícara'),
    ('Omelete de forno', 'Tomate', 1, 'unidade'), ('Omelete de forno', 'Queijo', 50, 'g'),
    ('Wrap de frango', 'Tortilha integral', 1, 'unidade'), ('Wrap de frango', 'Frango', 120, 'g'),
    ('Wrap de frango', 'Alface', 2, 'folha'), ('Wrap de frango', 'Tomate', 0.5, 'unidade'),
    ('Salmão com batata-doce', 'Salmão', 200, 'g'), ('Salmão com batata-doce', 'Batata-doce', 1, 'unidade'),
    ('Salmão com batata-doce', 'Brócolis', 1, 'xícara'), ('Salmão com batata-doce', 'Azeite de oliva', 2, 'colher de sopa'),
    ('Panqueca de banana', 'Banana', 1, 'unidade'), ('Panqueca de banana', 'Ovo', 1, 'unidade'),
    ('Panqueca de banana', 'Aveia', 3, 'colher de sopa'),
    ('Bowl de quinoa', 'Quinoa', 0.5, 'xícara'), ('Bowl de quinoa', 'Abacate', 0.5, 'unidade'),
    ('Bowl de quinoa', 'Tomate', 1, 'unidade'), ('Bowl de quinoa', 'Cenoura', 1, 'unidade'),
    ('Arroz, feijão e frango grelhado', 'Arroz', 1, 'xícara'), ('Arroz, feijão e frango grelhado', 'Feijão', 1, 'concha'),
    ('Arroz, feijão e frango grelhado', 'Frango', 150, 'g'),
    ('Iogurte com morango e mel', 'Iogurte natural', 170, 'g'), ('Iogurte com morango e mel', 'Morango', 5, 'unidade'),
    ('Iogurte com morango e mel', 'Mel', 1, 'colher de sopa'),
    ('Salada de frango com abacate', 'Frango', 120, 'g'), ('Salada de frango com abacate', 'Abacate', 0.5, 'unidade'),
    ('Salada de frango com abacate', 'Alface', 4, 'folha'), ('Salada de frango com abacate', 'Tomate', 1, 'unidade')
) AS v(recipe, ingredient, qty, unit)
JOIN recipe r ON r.name = v.recipe
JOIN ingredient i ON i.name = v.ingredient
ON CONFLICT DO NOTHING;
