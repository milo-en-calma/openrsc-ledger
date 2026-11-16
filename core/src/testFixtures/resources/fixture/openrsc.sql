-- A small OpenRSC game database for tests: the server's own table definitions
-- (server/database/sqlite/core.sqlite in OpenRSC) for the tables the ledger reads,
-- holding invented players. The password, salt, email and IP values are placeholders,
-- there so tests can check the ledger never reads them.

CREATE TABLE IF NOT EXISTS `players`
(
    `id`                INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    `username`          varchar(12)  NOT NULL DEFAULT '',
    `group_id`          int(10)               DEFAULT 10,
    `email`             varchar(255)          DEFAULT NULL,
    `pass`              varchar(512) NOT NULL,
    `salt`              varchar(250) NOT NULL DEFAULT '',
    `combat`            int(10)               DEFAULT 3,
    `skill_total`       int(10)               DEFAULT 27,
    `x`                 int(5)                DEFAULT 216,
    `y`                 int(5)                DEFAULT 451,
    `fatigue`           int(10)               DEFAULT 0,
    `combatstyle`       tinyint(1)            DEFAULT 0,
    `block_chat`        tinyint(1)            DEFAULT 0,
    `block_private`     tinyint(1)            DEFAULT 0,
    `block_trade`       tinyint(1)            DEFAULT 0,
    `block_duel`        tinyint(1)            DEFAULT 0,
    `cameraauto`        tinyint(1)            DEFAULT 1,
    `onemouse`          tinyint(1)            DEFAULT 0,
    `soundoff`          tinyint(1)            DEFAULT 0,
    `haircolour`        int(5)                DEFAULT 2,
    `topcolour`         int(5)                DEFAULT 8,
    `trousercolour`     int(5)                DEFAULT 14,
    `skincolour`        int(5)                DEFAULT 0,
    `headsprite`        int(5)                DEFAULT 1,
    `bodysprite`        int(5)                DEFAULT 2,
    `male`              tinyint(1)            DEFAULT 1,
    `creation_date`     int(10)      NOT NULL DEFAULT 0,
    `creation_ip`       varchar(255) NOT NULL DEFAULT '0.0.0.0',
    `login_date`        int(10)               DEFAULT 0,
    `login_ip`          varchar(255)          DEFAULT '0.0.0.0',
    `banned`            varchar(255) NOT NULL DEFAULT '0',
    `offences`          int(11)      NOT NULL DEFAULT 0,
    `muted`             varchar(255) NOT NULL DEFAULT '0',
    `kills`             int(10)      NOT NULL DEFAULT 0,
    `npc_kills`         INT(10)      NOT NULL DEFAULT 0,
    `deaths`            int(10)               DEFAULT 0,
    `online`            tinyint(1)            DEFAULT 0,
    `quest_points`      int(5)                DEFAULT NULL,
    `lastRecoveryTryId` int(10)               DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS `curstats`
(
    `playerID`   int(10)    NOT NULL PRIMARY KEY,
    `attack`     tinyint(3) NOT NULL DEFAULT 1,
    `defense`    tinyint(3) NOT NULL DEFAULT 1,
    `strength`   tinyint(3) NOT NULL DEFAULT 1,
    `hits`       tinyint(3) NOT NULL DEFAULT 10,
    `ranged`     tinyint(3) NOT NULL DEFAULT 1,
    `prayer`     tinyint(3) NOT NULL DEFAULT 1,
    `magic`      tinyint(3) NOT NULL DEFAULT 1,
    `cooking`    tinyint(3) NOT NULL DEFAULT 1,
    `woodcut`    tinyint(3) NOT NULL DEFAULT 1,
    `fletching`  tinyint(3) NOT NULL DEFAULT 1,
    `fishing`    tinyint(3) NOT NULL DEFAULT 1,
    `firemaking` tinyint(3) NOT NULL DEFAULT 1,
    `crafting`   tinyint(3) NOT NULL DEFAULT 1,
    `smithing`   tinyint(3) NOT NULL DEFAULT 1,
    `mining`     tinyint(3) NOT NULL DEFAULT 1,
    `herblaw`    tinyint(3) NOT NULL DEFAULT 1,
    `agility`    tinyint(3) NOT NULL DEFAULT 1,
    `thieving`   tinyint(3) NOT NULL DEFAULT 1,
    `harvesting` tinyint(3) NOT NULL DEFAULT 1,
    `runecraft`  tinyint(3) NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS `maxstats`
(
    `playerID`   int(10)    NOT NULL PRIMARY KEY,
    `attack`     tinyint(3) NOT NULL DEFAULT 1,
    `defense`    tinyint(3) NOT NULL DEFAULT 1,
    `strength`   tinyint(3) NOT NULL DEFAULT 1,
    `hits`       tinyint(3) NOT NULL DEFAULT 10,
    `ranged`     tinyint(3) NOT NULL DEFAULT 1,
    `prayer`     tinyint(3) NOT NULL DEFAULT 1,
    `magic`      tinyint(3) NOT NULL DEFAULT 1,
    `cooking`    tinyint(3) NOT NULL DEFAULT 1,
    `woodcut`    tinyint(3) NOT NULL DEFAULT 1,
    `fletching`  tinyint(3) NOT NULL DEFAULT 1,
    `fishing`    tinyint(3) NOT NULL DEFAULT 1,
    `firemaking` tinyint(3) NOT NULL DEFAULT 1,
    `crafting`   tinyint(3) NOT NULL DEFAULT 1,
    `smithing`   tinyint(3) NOT NULL DEFAULT 1,
    `mining`     tinyint(3) NOT NULL DEFAULT 1,
    `herblaw`    tinyint(3) NOT NULL DEFAULT 1,
    `agility`    tinyint(3) NOT NULL DEFAULT 1,
    `thieving`   tinyint(3) NOT NULL DEFAULT 1,
    `harvesting` tinyint(3) NOT NULL DEFAULT 1,
    `runecraft`  tinyint(3) NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS `experience`
(
    `playerID`   int(10) NOT NULL PRIMARY KEY,
    `attack`     int(9)  NOT NULL DEFAULT 0,
    `defense`    int(9)  NOT NULL DEFAULT 0,
    `strength`   int(9)  NOT NULL DEFAULT 0,
    `hits`       int(9)  NOT NULL DEFAULT 4616,
    `ranged`     int(9)  NOT NULL DEFAULT 0,
    `prayer`     int(9)  NOT NULL DEFAULT 0,
    `magic`      int(9)  NOT NULL DEFAULT 0,
    `cooking`    int(9)  NOT NULL DEFAULT 0,
    `woodcut`    int(9)  NOT NULL DEFAULT 0,
    `fletching`  int(9)  NOT NULL DEFAULT 0,
    `fishing`    int(9)  NOT NULL DEFAULT 0,
    `firemaking` int(9)  NOT NULL DEFAULT 0,
    `crafting`   int(9)  NOT NULL DEFAULT 0,
    `smithing`   int(9)  NOT NULL DEFAULT 0,
    `mining`     int(9)  NOT NULL DEFAULT 0,
    `herblaw`    int(9)  NOT NULL DEFAULT 0,
    `agility`    int(9)  NOT NULL DEFAULT 0,
    `thieving`   int(9)  NOT NULL DEFAULT 0,
    `harvesting` int(9)  NOT NULL DEFAULT 0,
    `runecraft`  int(9)  NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS `quests`
(
    `dbid`     INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    `playerID` int(10) NOT NULL,
    `id`       int(10) DEFAULT NULL,
    `stage`    int(10) DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS `invitems`
(
    `playerID` int(10) NOT NULL,
    `itemID`   int(10) NOT NULL,
    `slot`     int(5)  NOT NULL,
    PRIMARY KEY (`playerId`, `slot`)
);

CREATE TABLE IF NOT EXISTS `bank`
(
    `playerID` INTEGER NOT NULL,
    `itemID`   INTEGER NOT NULL,
    `slot`     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (`playerId`, `itemId`, `slot`)
);

-- Made by the server's add_equipment_tab script, used only with want_equipment_tab on.
CREATE TABLE IF NOT EXISTS `equipped`
(
    `playerID` int(10) NOT NULL,
    `itemID`   int(10) NOT NULL
);

CREATE TABLE IF NOT EXISTS `itemstatuses`
(
    `itemID`     INTEGER    NOT NULL PRIMARY KEY AUTOINCREMENT,
    `catalogID`  int(10)    NOT NULL,
    `amount`     int(10)    NOT NULL DEFAULT 1,
    `noted`      tinyint(1) NOT NULL DEFAULT 0,
    `wielded`    tinyint(1) NOT NULL DEFAULT 0,
    `durability` int(5)     NOT NULL DEFAULT 0
);

-- Three invented players: a seasoned one, a new one, and one online now.
INSERT INTO `players` (`id`, `username`, `email`, `pass`, `salt`, `combat`, `skill_total`,
                       `creation_date`, `creation_ip`, `login_date`, `login_ip`, `online`, `quest_points`)
VALUES (1, 'Bramblewick', 'bramblewick@example.invalid', 'placeholder-hash-1', 'placeholder-salt-1',
        32, 210, 1700000000, '192.0.2.10', 1730000000, '192.0.2.11', 0, 7),
       (2, 'Tansy Moss', 'tansy@example.invalid', 'placeholder-hash-2', 'placeholder-salt-2',
        3, 27, 1720000000, '192.0.2.20', 1720000500, '192.0.2.21', 0, 0),
       (3, 'Oakhollow', 'oakhollow@example.invalid', 'placeholder-hash-3', 'placeholder-salt-3',
        14, 95, 1710000000, '192.0.2.30', 1731000000, '192.0.2.31', 1, 2);

-- Stat rows start from the schema's defaults (a new player); later tests change them.
INSERT INTO `curstats` (`playerID`) VALUES (1), (2), (3);
INSERT INTO `maxstats` (`playerID`) VALUES (1), (2), (3);
INSERT INTO `experience` (`playerID`) VALUES (1), (2), (3);

-- Quest progress: Bramblewick finished quest 0 and is part way through quest 3;
-- Oakhollow finished quest 2. A stage of -1 means finished.
INSERT INTO `quests` (`playerID`, `id`, `stage`)
VALUES (1, 3, 2),
       (1, 0, -1),
       (3, 2, -1);

-- Belongings. Each row of invitems and bank points at an itemstatuses row holding the
-- item's type (catalogID), amount, and whether it's noted or worn. Bramblewick wears an
-- Iron Mace and a Wooden Shield and carries coins and a pickaxe; their bank holds coins,
-- noted logs and lobsters. Tansy Moss has nothing.
--
-- Oakhollow's Leather Armour and Bronze Short Sword are in `equipped`, as on a server
-- with its equipment tab on, so they count only when the tab is; the sword's row isn't
-- marked worn, as the server's SQLite save can leave it.
INSERT INTO `itemstatuses` (`itemID`, `catalogID`, `amount`, `noted`, `wielded`)
VALUES (101, 0, 1, 0, 1),
       (102, 4, 1, 0, 1),
       (103, 10, 1250, 0, 0),
       (104, 156, 1, 0, 0),
       (201, 10, 50000, 0, 0),
       (202, 14, 120, 1, 0),
       (203, 373, 1, 0, 0),
       (301, 20, 1, 0, 0),
       (302, 15, 1, 0, 1),
       (303, 66, 1, 0, 0);
INSERT INTO `invitems` (`playerID`, `itemID`, `slot`)
VALUES (1, 103, 2),
       (1, 101, 0),
       (1, 102, 1),
       (1, 104, 3),
       (3, 301, 0);
INSERT INTO `bank` (`playerID`, `itemID`, `slot`)
VALUES (1, 202, 1),
       (1, 201, 0),
       (1, 203, 2);
INSERT INTO `equipped` (`playerID`, `itemID`)
VALUES (3, 303),
       (3, 302);
