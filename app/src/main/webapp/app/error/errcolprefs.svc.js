esbMessageAdminApp.service('errorColumnPrefs', [
  'localStorage',
  function(localStorage) {
    var self = this;
    var error_column_storage_key = 'esb_message_admin.error_columns';
    var persistent = ['field', 'visible'];
    var standardCellTemplate = '<div class="ngCellText" ng-class="col.colIndex()" title="{{row.getProperty(col.field)}}">{{row.getProperty(col.field)}}</div>';
    var dateCellTempate = '<div class="ngCellText" ng-class="col.colIndex()">{{row.getProperty(col.field) | date:"M/d/yy HH:mm:ss"}}</div>';
    self.defaults = [
      // FIXME should include business key (issue #59)
      {
        field: 'sourceSystem',
        displayName: 'Source',
        width: '****',
        cellTemplate: standardCellTemplate,
      }, {
        field: 'errorSystem',
        displayName: 'Error System',
        width: '****',
        cellTemplate: standardCellTemplate,
      }, {
        field: 'messageType',
        displayName: 'Type',
        width: '****',
        cellTemplate: standardCellTemplate,
      }, {
        field: 'errorType',
        displayName: 'Error Type',
        width: '****',
        cellTemplate: standardCellTemplate,
        visible: false,
      }, {
        field: 'timestamp',
        displayName: 'Timestamp',
        width: '****',
        cellTemplate: dateCellTempate,
      }, {
        field: 'occurrenceCount',
        displayName: '#',
        width: '*',
        cellTemplate: standardCellTemplate,
      },
    ];

    self.default_map = {};
    for (var i in self.defaults) {
      var column = self.defaults[i];
      self.default_map[column.field] = column;
    };

    self.save = function(columns) {
      var sanitized_columns = [];
      for (var i in columns) {
        var column = columns[i];
        var sanitized_column = {};
        for (var i in persistent) {
          var property = persistent[i];
          sanitized_column[property] = column[property];
        }
        sanitized_columns.push(sanitized_column);
      }
      localStorage.setItem(error_column_storage_key, JSON.stringify(sanitized_columns));
    };

    function add_missing_fields(columns, loaded) {
      for (var field_name in self.default_map) {
        if (!(field_name in loaded)) {
          columns.push(self.default_map[field_name]);
        }
      }
    }

    function get_defaults(name) {
      var column = {};
      var default_setting = self.default_map[name];
      for (var property in default_setting) {
        column[property] = default_setting[property];
      }
      return column;
    }

    function merge_persistent_settings(destination, source) {
      for (var i in persistent) {
        var property = persistent[i];
        destination[property] = source[property];
      }
    }

    function sanitize(columns) {
      var sanitized_columns = [];
      var loaded = {};
      for (var i in columns) {
        var column = columns[i];
        var sanitized_column = get_defaults(column.field);
        merge_persistent_settings(sanitized_column, column);
        sanitized_columns.push(sanitized_column);
        loaded[column.field] = true;
      }
      add_missing_fields(sanitized_columns, loaded);

      return sanitized_columns;
    }

    this.load = function() {
      var saved = localStorage.getItem(error_column_storage_key);
      if (saved) {
        return sanitize(JSON.parse(saved));
      }
      return self.defaults;
    }
  }
]);
